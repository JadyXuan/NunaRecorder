package com.example.nunarecorder.recording

import com.example.nunarecorder.ble.OpusStreamAssembler
import com.example.nunarecorder.ble.SegmentOpusWriter
import com.example.nunarecorder.session.AudioSegmentEntry
import com.example.nunarecorder.session.LinkGapEntry
import com.example.nunarecorder.session.LinkHealth
import com.example.nunarecorder.session.SegmentFrameAccumulator
import com.example.nunarecorder.session.SegmentFrameStats
import com.example.nunarecorder.session.SessionManifest
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
    val receivedFrames: Long,
    val lostFrames: Long,
    val disconnectCount: Int,
    /** 当前链路是否处于中断状态 */
    val linkDown: Boolean
) {
    /** 序号空洞占「应收帧数」的比例；没收到过帧时为 0 */
    val lossRatio: Float
        get() {
            val total = receivedFrames + lostFrames
            return if (total <= 0) 0f else lostFrames.toFloat() / total
        }
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
 */
class SessionRecorder(
    private val onLog: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 分段封口回调，宿主用它入队 VAD；录制逻辑本身不依赖 Android */
    private val onSegmentClosed: (ClosedSegment) -> Unit = {}
) {

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
    private var receivedFrames = 0L
    private var lostFrames = 0L

    val activeSessionDir: File? get() = sessionDir

    val isRecording: Boolean get() = sessionDir != null

    val isLinkDown: Boolean get() = openGapStartMs != null

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
            receivedFrames = receivedFrames,
            lostFrames = lostFrames,
            disconnectCount = linkEvents.size + (if (openGapStartMs != null) 1 else 0),
            linkDown = openGapStartMs != null
        )
    }

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
        linkEvents.clear()
        missingSegments.clear()
        openGapStartMs = null
        openGapAttempts = 0
        lastFrameAtMs = null
        receivedFrames = 0L
        lostFrames = 0L
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
            recordingActive = true
        )
        SessionManifestIO.write(sessionDir, manifest!!)
        openSegment(0)
        onLog("开始录制 → ${sessionDir.name}")
    }

    /** 喂入一个 A003 notification 的原始字节。 */
    fun feed(data: ByteArray) {
        if (sessionDir == null) return
        val frames = assembler.feed(data)
        val now = clock()
        if (frames.isNotEmpty()) {
            maybeRotateSegment(now)
            for (frame in frames) {
                writer?.write(frame.opus)
                accumulator.onFrame(frame)
                receivedFrames++
                lostFrames += frame.missingBefore
            }
            lastFrameAtMs = now
        }
        maybeFlushManifest()
    }

    /**
     * 由宿主按秒调用。没有音频时也要推进分段轮转——否则断连期间时间轴会静默塌陷。
     */
    fun tick() {
        if (sessionDir == null) return
        maybeRotateSegment(clock())
        maybeFlushManifest()
    }

    /** BLE 断开。会话保持打开，等待重连续写。 */
    fun onLinkLost(reason: String) {
        if (sessionDir == null || openGapStartMs != null) return
        assembler.onLinkInterrupted()
        openGapStartMs = clock()
        openGapReason = reason
        openGapAttempts = 0
        onLog("链路中断：$reason")
        flushManifestNow()
    }

    fun onReconnectAttempt() {
        if (openGapStartMs != null) openGapAttempts++
    }

    /** 重连成功，继续写**同一个**会话。 */
    fun onLinkRestored() {
        val start = openGapStartMs ?: return
        val now = clock()
        linkEvents.add(LinkGapEntry(start, now, openGapReason, openGapAttempts))
        openGapStartMs = null
        openGapAttempts = 0
        onLog("链路已恢复，中断 ${(now - start) / 1000} 秒（已记入 manifest）")
        flushManifestNow()
    }

    fun stop() {
        val dir = sessionDir ?: return
        val now = clock()
        closeCurrentSegment(now)
        openGapStartMs?.let {
            // 会话在链路仍然断开时结束：end 留 null，不要假装它恢复过
            linkEvents.add(LinkGapEntry(it, null, openGapReason, openGapAttempts))
            openGapStartMs = null
        }
        manifest?.let { m ->
            m.endedAtMs = now
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
        val stats = accumulator.snapshot(SegmentFrameStats.expectedFramesFor(windowMs))
        accumulator = SegmentFrameAccumulator()

        if (bytes == 0L) {
            file.delete()
            // 只有覆盖了真实墙钟时间的段才算"缺失"。停止录制的瞬间刚轮转出来的那一段
            // 窗口长度为 0，没有任何东西被期望过，把它记成空洞是在虚报。
            if (options.segmentEnabled && stats.expectedFrames > 0) {
                missingSegments.add(currentSegmentIndex)
                onLog("分段 $currentSegmentIndex 没有任何音频（期望 ${stats.expectedFrames} 帧），已记入 missing_segments")
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
                "${stats.receivedFrames}/${stats.expectedFrames} 帧" +
                if (stats.sequenceLostFrames > 0) " · 空洞 ${stats.sequenceLostFrames} 帧" else ""
        )
        onSegmentClosed(ClosedSegment(dir, entry, file, sessionStartMs))
    }

    private fun maybeFlushManifest() {
        if (clock() - lastManifestFlushMs < 1000L) return
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
