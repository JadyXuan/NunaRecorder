package com.example.nunarecorder.recording

import com.example.nunarecorder.ble.OpusStreamAssembler
import com.example.nunarecorder.ble.SegmentOpusWriter
import com.example.nunarecorder.session.AudioSegmentEntry
import com.example.nunarecorder.session.LinkGapEntry
import com.example.nunarecorder.session.LinkHealth
import com.example.nunarecorder.session.SegmentFrameAccumulator
import com.example.nunarecorder.session.SegmentFrameStats
import com.example.nunarecorder.session.MmWaveSummary
import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionModalities
import com.example.nunarecorder.session.SessionManifestIO
import com.example.nunarecorder.session.SessionPaths
import com.example.nunarecorder.session.VadSummary
import java.io.File

data class LiveRecordingStats(
    val sessionDir: File,
    val closedSegmentCount: Int,
    val closedBytes: Long,
    val openSegmentBytes: Long,
    val totalBytes: Long,
    /** 最近一次收到音频帧的墙钟；null = 本次会话还没收到过一帧 */
    val lastFrameAtMs: Long?,
    /** 实到的 20 ms Opus 包数 */
    val receivedPackets: Long,
    /** 按墙钟应有的 20 ms 包数 */
    val expectedPackets: Long,
    val disconnectCount: Int,
    /** 当前链路是否处于中断状态 */
    val linkDown: Boolean
) {
    /** 完整度 0..1：实到包数 / 按墙钟应有的包数 */
    val completeness: Float
        get() = if (expectedPackets <= 0L) 1f
        else (receivedPackets.toFloat() / expectedPackets).coerceIn(0f, 1f)
}

/**
 * 会话录制：按墙钟轮转 Opus 分段，记录每段的帧账目和链路中断区间。
 *
 * 相对 2026-07-31 实测版本的三处行为变化：
 *
 * 1. **轮转由墙钟驱动**（[tick]），不再只在 [feed] 里发生。原来 BLE 一断，`feed` 不再被调用，
 *    分段轮转随之停止而时间继续走，于是断连后的整段时间既没有音频也没有任何记录。
 * 2. **分段序号由时间推导**，不再取 `manifest.segments.size`。旧写法在出现空段时
 *    （恰恰就是断连期间）会让 `while (currentSegmentIndex < expectedIndex)` 永远不前进——
 *    一个真实存在的死循环，很可能就是佩戴者报告的"闪退"。
 * 3. **空段不再被静默跳过**：索引空洞写进 `audio.missing_segments`，
 *    断连区间写进 `link.events`，每段的期望/实到/丢失帧数写进 `audio.segments[].frames`。
 *
 * 这个类不碰 Android API（除了通过 [SegmentOpusWriter] 记录写失败），
 * 会话目录、时钟和 VAD 入队都从外部注入，因此可以直接跑 JVM 单测。
 *
 * **线程安全**：[feed] 在 BLE 回调线程上被调用（50 次/秒，刻意不绕主线程），
 * 而 [tick] 和 [liveStats] 在服务的主线程 Handler 上。两边都会动分段轮转状态，
 * 所以所有对外方法都加锁。锁内只有一次文件写，无竞争时的开销可以忽略。
 */
class SessionRecorder(
    private val onLog: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 分段封口回调，宿主用它入队 VAD；录制逻辑本身不依赖 Android */
    private val onSegmentClosed: (ClosedSegment) -> Unit = {},
    /** 采集端版本，写进 manifest 供审计和排查用 */
    private val appVersion: String? = null
) {

    companion object {
        /** 周期性兜底落盘间隔；真正的落盘由分段封口和链路事件驱动 */
        private const val MANIFEST_FLUSH_PERIOD_MS = 30_000L
    }

    data class ClosedSegment(
        val sessionDir: File,
        val entry: AudioSegmentEntry,
        val file: File,
        val sessionStartedAtMs: Long
    )

    private var sessionDir: File? = null
    private var manifest: SessionManifest? = null
    private var writer: SegmentOpusWriter? = null
    private var currentSegmentFile: File? = null
    private var currentSegmentIndex = 0
    private var sessionStartMs = 0L
    private var lastManifestFlushMs = 0L

    /** 会话级：跨分段保持，这样段边界上的半条消息和未凑齐的帧不再每分钟丢一次 */
    private val assembler = OpusStreamAssembler()
    private var accumulator = SegmentFrameAccumulator()

    private var options: RecordingOptions = RecordingOptions(
        segmentEnabled = true,
        segmentDurationMs = SessionPaths.SEGMENT_DURATION_MS,
        autoVadOnRecord = true
    )

    private val linkEvents = mutableListOf<LinkGapEntry>()
    private val missingSegments = mutableListOf<Int>()
    private var openGapStartMs: Long? = null
    private var openGapReason: String = ""
    private var openGapAttempts: Int = 0

    private var lastFrameAtMs: Long? = null
    private var receivedPackets = 0L
    private var expectedPackets = 0L

    val activeSessionDir: File? get() = sessionDir

    val isRecording: Boolean get() = sessionDir != null

    val isLinkDown: Boolean get() = openGapStartMs != null

    @Synchronized
    fun liveStats(): LiveRecordingStats? {
        val dir = sessionDir ?: return null
        val closedBytes = manifest?.segments?.sumOf { it.bytes } ?: 0L
        val openBytes = writer?.bytesWritten ?: 0L
        return LiveRecordingStats(
            sessionDir = dir,
            closedSegmentCount = manifest?.segments?.size ?: 0,
            closedBytes = closedBytes,
            openSegmentBytes = openBytes,
            totalBytes = closedBytes + openBytes,
            lastFrameAtMs = lastFrameAtMs,
            receivedPackets = receivedPackets,
            expectedPackets = expectedPackets,
            disconnectCount = linkEvents.size + (if (openGapStartMs != null) 1 else 0),
            linkDown = openGapStartMs != null
        )
    }

    @Synchronized
    fun start(
        sessionDir: File,
        deviceName: String,
        deviceAddress: String?,
        recordingOptions: RecordingOptions
    ) {
        stop()
        options = recordingOptions
        sessionStartMs = clock()
        this.sessionDir = sessionDir
        assembler.reset()
        runCatching { mmWave.start(sessionDir, sessionStartMs) }
        linkEvents.clear()
        missingSegments.clear()
        openGapStartMs = null
        openGapAttempts = 0
        lastFrameAtMs = null
        receivedPackets = 0L
        expectedPackets = 0L
        manifest = SessionManifest(
            sessionId = sessionDir.name,
            deviceName = deviceName,
            deviceAddress = deviceAddress,
            startedAtMs = sessionStartMs,
            segmentDurationMs = if (options.segmentEnabled) options.segmentDurationMs
            else Long.MAX_VALUE,
            vad = VadSummary(
                status = if (options.autoVadOnRecord) "pending" else "disabled"
            ),
            recordingActive = true,
            appVersion = appVersion,
            // 毫米波由设备固件自己开关，App 不请求也会收到；所以模态里直接声明它，
            // 收不到就在 stop 时落成 no_data，而不是假装没这个模态。
            contextModalities = SessionModalities.forRecording(true),
            mmWave = MmWaveSummary.initial(true)
        )
        SessionManifestIO.write(sessionDir, manifest!!)
        openSegment(0)
        onLog("开始录制 → ${sessionDir.name}")
    }

    /** 喂入一个 A003 notification 的原始字节。 */
    @Synchronized
    fun feed(data: ByteArray) {
        if (sessionDir == null) return
        val frames = assembler.feed(data)
        val now = clock()
        if (frames.isNotEmpty()) {
            maybeRotateSegment(now)
            for (frame in frames) {
                writer?.write(frame.opus)
                accumulator.onFrame(frame)
                receivedPackets += frame.opus.size / OpusStreamAssembler.OPUS_FRAME_SIZE
            }
            // 会话级"应有包数"按墙钟算，和每段一致
            expectedPackets = SegmentFrameStats.expectedPacketsFor(
                (now - sessionStartMs).coerceAtLeast(0L)
            ).toLong()
            lastFrameAtMs = now
        }
        // feed() 是 50 次/秒的热路径，不在这里碰 manifest。
        // 落盘由 tick() 的节流兜底和分段封口驱动。
    }

    /**
     * 由宿主按秒调用。没有音频时也要推进分段轮转——否则断连期间时间轴会静默塌陷。
     */
    @Synchronized
    fun tick() {
        if (sessionDir == null) return
        maybeRotateSegment(clock())
        maybeFlushManifest()
    }

    /** BLE 断开。会话保持打开，等待重连续写。 */
    @Synchronized
    fun onLinkLost(reason: String) {
        if (sessionDir == null || openGapStartMs != null) return
        assembler.onLinkInterrupted()
        openGapStartMs = clock()
        openGapReason = reason
        openGapAttempts = 0
        onLog("链路中断：$reason")
        flushManifestNow()
    }

    /** 毫米波写入器；数据和开关时间线都归它 */
    private val mmWave = MmWaveSessionWriter()

    /**
     * 毫米波原始包。**任何异常都不得影响音频**——这一路是附加模态，
     * 写不进去最多少一个模态，而音频是任务本身。
     */
    fun feedSensorPacket(raw: ByteArray) {
        if (sessionDir == null) return
        runCatching { mmWave.feed(raw) }
    }

    /**
     * 毫米波开关。固件自己 30s 开 / 30s 关，开关瞬间会短暂干扰几帧音频，
     * 所以除了写进 mmwave_state.jsonl，还要在 link.events 里留一条——
     * **主动扰动必须和链路故障分开记**，否则事后看只是"这几帧没了"。
     */
    @Synchronized
    fun onRadarState(enabled: Boolean) {
        val dir = sessionDir ?: return
        val now = clock()
        runCatching {
            mmWave.recordState(
                enabled = enabled,
                requestedByApp = false,
                receivedAtMs = now,
                sessionOffsetMs = (now - (manifest?.startedAtMs ?: now)).coerceAtLeast(0L),
                source = "device_0x13"
            )
        }
        if (!enabled) {
            // 只在关闭时记一条：开启瞬间的扰动和关闭瞬间是同一类，
            // 但两条会把 events 撑成一天几百条。关闭点足以定位那一对边界。
            linkEvents.add(LinkGapEntry(now, now, "mmwave_toggle", reconnectAttempts = 0))
        }
    }

    /** 设备固件版本；连上之后才读得到，所以是后置写入而不是构造参数。 */
    fun setDeviceFirmware(version: String) {
        val m = manifest ?: return
        if (m.deviceFirmware == version) return
        m.deviceFirmware = version
        flushManifestNow()
    }

    fun onReconnectAttempt() {
        if (openGapStartMs != null) openGapAttempts++
    }

    /** 重连成功，继续写**同一个**会话。 */
    @Synchronized
    fun onLinkRestored() {
        val start = openGapStartMs ?: return
        val now = clock()
        linkEvents.add(LinkGapEntry(start, now, openGapReason, openGapAttempts))
        openGapStartMs = null
        openGapAttempts = 0
        onLog("链路已恢复，中断 ${(now - start) / 1000} 秒（已记入 manifest）")
        flushManifestNow()
    }

    /**
     * 会话结束时把诊断日志快照写进会话目录，它会随会话一起上传。
     *
     * 佩戴者要单独导出日志再手工发出来，实际上没人会做；而排查断连、丢帧、
     * 上传失败恰恰最需要它。日志只有结构化事件，几十 KB，相对音频可以忽略。
     */
    var diagnosticsSnapshot: (() -> String)? = null

    @Synchronized
    fun stop(endReason: String = SessionManifest.END_USER_STOP) {
        val dir = sessionDir ?: return
        val mmStats = runCatching { mmWave.stop() }.getOrNull()
        val now = clock()
        closeCurrentSegment(now)
        openGapStartMs?.let {
            // 会话在链路仍然断开时结束：end 留 null，不要假装它恢复过
            linkEvents.add(LinkGapEntry(it, null, openGapReason, openGapAttempts))
            openGapStartMs = null
        }
        diagnosticsSnapshot?.let { snapshot ->
            runCatching {
                File(dir, SessionPaths.DIAGNOSTICS_LOG_FILE).also { it.parentFile?.mkdirs() }
                    .writeText(snapshot())
            }
        }
        manifest?.let { m ->
            m.endedAtMs = now
            m.endReason = endReason
            mmStats?.let { st ->
                m.mmWave = m.mmWave.copy(
                    status = if (st.packetCount > 0) MmWaveSummary.STATUS_CAPTURED
                    else MmWaveSummary.STATUS_NO_DATA,
                    packetCount = st.packetCount,
                    payloadBytes = st.payloadBytes,
                    fileBytes = SessionPaths.mmWaveFile(dir).length().takeIf { it > 0L },
                    malformedPackets = st.malformedPackets,
                    stateEventCount = st.stateEventCount,
                    stateFileBytes = SessionPaths.mmWaveStateFile(dir).length().takeIf { it > 0L }
                )
            }
            m.recordingActive = false
            m.openSegmentIndex = null
            m.openSegmentBytes = 0L
            m.link = linkHealth()
            m.missingSegments = missingSegments.toList()
            SessionManifestIO.write(dir, m)
        }
        writer = null
        currentSegmentFile = null
        sessionDir = null
        manifest = null
        assembler.reset()
        onLog("录制已停止")
    }

    /**
     * 记一段**有意为之**的空档（目前只有录声纹）。
     *
     * 空档必须写进 manifest，否则事后看只是"这一分钟没有音频"，和掉线一模一样。
     * reason 用 `voiceprint_capture` 而不是断连原因，读数据的人一眼能分清
     * "我们主动停的"和"链路坏了"。
     */
    @Synchronized
    fun noteIntentionalGap(startMs: Long, endMs: Long, reason: String) {
        if (sessionDir == null) return
        linkEvents.add(LinkGapEntry(startMs, endMs, reason, reconnectAttempts = 0))
        flushManifestNow()
    }

    private fun linkHealth(): LinkHealth {
        val d = assembler.diagnostics()
        val events = linkEvents.toMutableList()
        openGapStartMs?.let { events.add(LinkGapEntry(it, null, openGapReason, openGapAttempts)) }
        return LinkHealth(
            events = events,
            resyncSkippedBytes = d.resyncSkippedBytes,
            incompleteFrames = d.incompleteFrames,
            duplicateFrames = d.duplicateFrames,
            reorderedFrames = d.reorderedFrames,
            droppedCarryOverBytes = d.droppedCarryOverBytes
        )
    }

    private fun segmentStartMs(index: Int): Long =
        if (options.segmentEnabled) index * options.segmentDurationMs else 0L

    private fun maybeRotateSegment(now: Long) {
        if (!options.segmentEnabled) return
        val duration = options.segmentDurationMs
        if (duration <= 0) return
        val expectedIndex = ((now - sessionStartMs) / duration).toInt()
        while (currentSegmentIndex < expectedIndex) {
            closeCurrentSegment(now)
            openSegment(currentSegmentIndex + 1)
        }
    }

    private fun openSegment(index: Int) {
        val dir = sessionDir ?: return
        SessionPaths.audioDir(dir).mkdirs()
        currentSegmentIndex = index
        val rel = if (options.segmentEnabled) {
            SessionPaths.segmentRelativePath(index)
        } else {
            SessionPaths.STREAM_OPUS_FILE
        }
        val file = File(dir, rel)
        currentSegmentFile = file
        writer = SegmentOpusWriter(file)
        accumulator = SegmentFrameAccumulator()
        flushManifestNow()
    }

    private fun closeCurrentSegment(now: Long) {
        val dir = sessionDir ?: return
        val file = currentSegmentFile ?: return
        writer?.close()
        val bytes = writer?.bytesWritten ?: 0L
        writer = null
        currentSegmentFile = null

        val startMs = segmentStartMs(currentSegmentIndex)
        // 最后一段通常不满一整格，按整格算会凭空多出一堆"丢失"
        val windowMs = if (options.segmentEnabled) {
            (now - sessionStartMs - startMs).coerceIn(0L, options.segmentDurationMs)
        } else {
            (now - sessionStartMs).coerceAtLeast(0L)
        }
        val stats = accumulator.snapshot(SegmentFrameStats.expectedPacketsFor(windowMs))
        accumulator = SegmentFrameAccumulator()

        if (bytes == 0L) {
            file.delete()
            // 只有覆盖了真实墙钟时间的段才算"缺失"。停止录制的瞬间刚轮转出来的那一段
            // 窗口长度为 0，没有任何东西被期望过，把它记成空洞是在虚报。
            if (options.segmentEnabled && stats.expectedPackets > 0) {
                missingSegments.add(currentSegmentIndex)
                onLog(
                    "分段 $currentSegmentIndex 没有任何音频" +
                        "（期望 ${stats.expectedPackets} 个 20ms 包），已记入 missing_segments"
                )
            }
            flushManifestNow()
            return
        }

        val frameBytes = OpusStreamAssembler.OPUS_FRAME_SIZE
        val durationMs = bytes / frameBytes * OpusStreamAssembler.FRAME_DURATION_MS
        val maxDuration = if (options.segmentEnabled) options.segmentDurationMs else Long.MAX_VALUE
        val cappedDuration = durationMs.coerceAtMost(maxDuration)
        val rel = if (options.segmentEnabled) {
            SessionPaths.segmentRelativePath(currentSegmentIndex)
        } else {
            SessionPaths.STREAM_OPUS_FILE
        }
        val entry = AudioSegmentEntry(
            index = currentSegmentIndex,
            file = rel,
            startMs = startMs,
            endMs = startMs + cappedDuration,
            bytes = bytes,
            durationMs = cappedDuration,
            frames = stats
        )
        manifest?.segments?.add(entry)
        manifest?.let { SessionManifestIO.write(dir, it) }
        onLog(
            "分段 ${entry.index} 已保存 · ${formatSize(bytes)} · " +
                "完整度 %.0f%%（%d/%d 个 20ms 包）".format(
                    stats.completeness * 100, stats.receivedPackets, stats.expectedPackets
                ) +
                if (stats.deviceSequenceLost > 0) " · 设备帧空洞 ${stats.deviceSequenceLost}" else ""
        )
        onSegmentClosed(ClosedSegment(dir, entry, file, sessionStartMs))
    }

    /**
     * manifest 落盘节流。
     *
     * 原来是**每秒**重写一次整份 manifest，而 manifest 随会话线性增长
     * （2026-08-06 实测：93 段 34 KB，16 小时 960 段约 345 KB）。
     * 每秒序列化 + 原子写（写临时文件再 rename）一份几百 KB 的 JSON，
     * 16 小时累计约 9 GB 写入 —— 这是佩戴者报告"手机很热、掉电很快"的主因。
     *
     * 现在只在**真正有事发生**时落盘：分段封口、链路中断、链路恢复、停止录制。
     * 这里的周期性兜底放宽到 [MANIFEST_FLUSH_PERIOD_MS]，进程被杀最多丢这么久的
     * "当前段已写入字节数"——那是纯展示用的数字，音频本身已经在文件里了。
     */
    private fun maybeFlushManifest() {
        if (clock() - lastManifestFlushMs < MANIFEST_FLUSH_PERIOD_MS) return
        flushManifestNow()
    }

    private fun flushManifestNow() {
        val dir = sessionDir ?: return
        val m = manifest ?: return
        lastManifestFlushMs = clock()
        m.recordingActive = true
        m.openSegmentIndex = currentSegmentIndex
        m.openSegmentBytes = writer?.bytesWritten ?: 0L
        m.link = linkHealth()
        m.missingSegments = missingSegments.toList()
        SessionManifestIO.write(dir, m)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
