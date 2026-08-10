package com.example.nunarecorder.recording

import android.os.SystemClock
import com.example.nunarecorder.ble.BleAudioReassembler
import com.example.nunarecorder.ble.NunaProtocolInspector
import com.example.nunarecorder.session.AudioSegmentEntry
import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionManifestIO
import com.example.nunarecorder.session.SessionModalities
import com.example.nunarecorder.session.SessionPaths
import com.example.nunarecorder.session.MmWaveSummary
import com.example.nunarecorder.session.VadSummary
import com.example.nunarecorder.vad.VadJob
import com.example.nunarecorder.vad.VadJobQueue
import com.example.nunarecorder.vad.VadPrelabelWriter
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class LiveRecordingStats(
    val sessionDir: File,
    val closedSegmentCount: Int,
    val closedBytes: Long,
    val openSegmentBytes: Long,
    val totalBytes: Long
)

/**
 * 会话录制：可选按墙钟时长轮转 Opus 段；段封口后可选入队 Silero VAD。
 */
class SessionRecorder(
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val monotonicClockMs: () -> Long = SystemClock::elapsedRealtime,
    private val sessionDirFactory: (String, String?, Long) -> File =
        { deviceName, deviceAddress, startedAtMs ->
            SessionPaths.newSessionDir(deviceName, deviceAddress, startedAtMs)
        },
    private val onLog: (String) -> Unit
) {
    private var sessionDir: File? = null
    private var manifest: SessionManifest? = null
    private var reassembler: BleAudioReassembler? = null
    private var currentSegmentIndex = 0
    private var currentSegmentFile: File? = null
    private var currentSegmentStartMs = 0L
    private var currentSegmentTimeSlot = 0L
    private var currentSegmentIntegrityIssue: String? = null
    private var currentSegmentTimelineIssueLogged = false
    private var currentSegmentHasAudioPacket = false
    private var sessionStartMs = 0L
    private var sessionStartMonotonicMs = 0L
    private var options: RecordingOptions = RecordingOptions(
        segmentEnabled = true,
        segmentDurationMs = SessionPaths.SEGMENT_DURATION_MS,
        autoVadOnRecord = true
    )
    private var lastManifestFlushMs = 0L
    private val mmWaveWriter = MmWaveSessionWriter(wallClockMs)
    private val audioTimelineWriter = AudioTimelineWriter()
    private val mmWaveExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "nuna-mmwave-writer").apply { isDaemon = true }
    }
    private val pendingMmWaveWrites = AtomicInteger(0)
    private val droppedMmWavePackets = AtomicLong(0L)
    private val mmWaveWriteFailureLogged = AtomicBoolean(false)
    @Volatile private var mmWaveAccepting = false

    val activeSessionDir: File? get() = sessionDir

    val isRecording: Boolean get() = sessionDir != null

    fun liveStats(): LiveRecordingStats? {
        val dir = sessionDir ?: return null
        val closedBytes = manifest?.segments?.sumOf { it.bytes } ?: 0L
        val openBytes = currentSegmentFile?.takeIf { it.exists() }?.length() ?: 0L
        return LiveRecordingStats(
            sessionDir = dir,
            closedSegmentCount = manifest?.segments?.size ?: 0,
            closedBytes = closedBytes,
            openSegmentBytes = openBytes,
            totalBytes = closedBytes + openBytes
        )
    }

    fun start(
        deviceName: String,
        deviceAddress: String?,
        recordingOptions: RecordingOptions
    ) {
        stop()
        options = recordingOptions
        sessionStartMs = wallClockMs()
        sessionStartMonotonicMs = monotonicClockMs()
        currentSegmentIndex = 0
        currentSegmentTimeSlot = 0L
        currentSegmentStartMs = 0L
        val dir = sessionDirFactory(deviceName, deviceAddress, sessionStartMs)
        sessionDir = dir
        manifest = SessionManifest(
            sessionId = dir.name,
            deviceName = deviceName,
            deviceAddress = deviceAddress,
            startedAtMs = sessionStartMs,
            segmentDurationMs = if (options.segmentEnabled) options.segmentDurationMs
            else Long.MAX_VALUE,
            vad = VadSummary(
                status = if (options.autoVadOnRecord) "pending" else "disabled"
            ),
            contextModalities = SessionModalities.forRecording(options.mmWaveCaptureEnabled),
            mmWave = MmWaveSummary.initial(options.mmWaveCaptureEnabled),
            recordingActive = true
        )
        SessionManifestIO.write(dir, manifest!!)
        droppedMmWavePackets.set(0L)
        mmWaveWriteFailureLogged.set(false)
        mmWaveAccepting = options.mmWaveCaptureEnabled
        if (mmWaveAccepting) {
            mmWaveExecutor.execute { mmWaveWriter.start(dir, sessionStartMs) }
        }
        audioTimelineWriter.start(dir)
        if (options.autoVadOnRecord) {
            VadPrelabelWriter.markRunning(dir, 0, sessionStartMs)
        }
        openCurrentSegment()
        onLog("开始录制 → ${dir.name}")
    }

    /**
     * Feeds one BLE notification and returns a newly detected integrity issue once.
     * A damaged segment remains excluded from VAD/automatic upload, while later audio can
     * continue to be recorded into this and subsequent segments.
     */
    fun feed(data: ByteArray): String? {
        val parsedAudio = NunaProtocolInspector.parseAudio(data)
        if (options.segmentEnabled) {
            maybeRotateSegment(parsedAudio)
        }
        val sessionOffsetMs = currentSessionOffsetMs()
        val receivedAtMs = sessionStartMs + sessionOffsetMs
        if (parsedAudio != null && !currentSegmentHasAudioPacket) {
            currentSegmentStartMs = sessionOffsetMs
            currentSegmentHasAudioPacket = true
        }
        parsedAudio?.let { packet ->
            audioTimelineWriter.recordPacket(
                packet = packet,
                receivedAtMs = receivedAtMs,
                sessionOffsetMs = sessionOffsetMs,
                segmentIndex = currentSegmentIndex,
                segmentStartMs = currentSegmentStartMs
            )
        }
        val activeReassembler = reassembler
        activeReassembler?.feed(data)
        val newIntegrityIssue = activeReassembler?.consumeNewIntegrityIssue()
        if (newIntegrityIssue != null) {
            currentSegmentTimelineIssueLogged = true
            audioTimelineWriter.recordIntegrityEvent(
                reason = newIntegrityIssue,
                receivedAtMs = receivedAtMs,
                sessionOffsetMs = sessionOffsetMs,
                segmentIndex = currentSegmentIndex,
                phase = "stream"
            )
        }
        maybeFlushManifest()
        return newIntegrityIssue
    }

    fun feedMmWave(raw: ByteArray): MmWaveCaptureResult {
        if (!mmWaveAccepting || sessionDir == null) return MmWaveCaptureResult.Inactive
        val sessionOffsetMs = currentSessionOffsetMs()
        val receivedAtMs = sessionStartMs + sessionOffsetMs
        val envelope = NunaProtocolInspector.parseEnvelope(raw)
            ?: return MmWaveCaptureResult.NotSensorData
        if (envelope.type != 0x08) return MmWaveCaptureResult.NotSensorData
        if (envelope.data.size < 9) {
            if (pendingMmWaveWrites.incrementAndGet() > MAX_PENDING_MMWAVE_PACKETS) {
                pendingMmWaveWrites.decrementAndGet()
                droppedMmWavePackets.incrementAndGet()
                return MmWaveCaptureResult.Dropped
            }
            mmWaveExecutor.execute {
                try {
                    mmWaveWriter.feed(raw.copyOf(), receivedAtMs, sessionOffsetMs)
                } finally {
                    pendingMmWaveWrites.decrementAndGet()
                }
            }
            return MmWaveCaptureResult.Malformed
        }
        if (pendingMmWaveWrites.incrementAndGet() > MAX_PENDING_MMWAVE_PACKETS) {
            pendingMmWaveWrites.decrementAndGet()
            droppedMmWavePackets.incrementAndGet()
            return MmWaveCaptureResult.Dropped
        }
        val packetCopy = raw.copyOf()
        mmWaveExecutor.execute {
            try {
                val result = mmWaveWriter.feed(packetCopy, receivedAtMs, sessionOffsetMs)
                if (result is MmWaveCaptureResult.WriteFailed &&
                    mmWaveWriteFailureLogged.compareAndSet(false, true)
                ) {
                    onLog("毫米波数据写入失败：${result.reason}")
                }
            } finally {
                pendingMmWaveWrites.decrementAndGet()
            }
        }
        return MmWaveCaptureResult.Queued
    }

    fun recordMmWaveState(enabled: Boolean, requestedByApp: Boolean, source: String) {
        if (!mmWaveAccepting || sessionDir == null) return
        val sessionOffsetMs = currentSessionOffsetMs()
        val receivedAtMs = sessionStartMs + sessionOffsetMs
        mmWaveExecutor.execute {
            mmWaveWriter.recordState(
                enabled = enabled,
                requestedByApp = requestedByApp,
                receivedAtMs = receivedAtMs,
                sessionOffsetMs = sessionOffsetMs,
                source = source
            )
        }
    }

    fun stop() {
        val dir = sessionDir ?: return
        mmWaveAccepting = false
        var mmWaveFinalizeTimedOut = false
        val mmWaveStats = runCatching {
            mmWaveExecutor.submit<MmWaveCaptureStats> { mmWaveWriter.stop() }
                .get(3, TimeUnit.SECONDS)
        }.getOrElse {
            mmWaveFinalizeTimedOut = true
            onLog("毫米波数据封口超时，后台仍将尝试完成写盘")
            MmWaveCaptureStats(0L, 0L, 0L, null, 0L, null)
        }
        val droppedMmWave = droppedMmWavePackets.getAndSet(0L)
        closeCurrentSegment(
            enqueueVad = options.autoVadOnRecord,
            allowIncompleteTail = true
        )
        audioTimelineWriter.stop()
        manifest?.endedAtMs = sessionStartMs + currentSessionOffsetMs()
        manifest?.recordingActive = false
        manifest?.openSegmentIndex = null
        manifest?.openSegmentBytes = 0L
        manifest?.mmWave = when {
            !options.mmWaveCaptureEnabled -> MmWaveSummary.initial(false)
            mmWaveFinalizeTimedOut -> MmWaveSummary(
                enabled = true,
                status = MmWaveSummary.STATUS_FINALIZE_TIMEOUT,
                droppedPackets = droppedMmWave
            )
            else -> MmWaveSummary(
                enabled = true,
                status = if (mmWaveStats.packetCount > 0L) {
                    MmWaveSummary.STATUS_CAPTURED
                } else {
                    MmWaveSummary.STATUS_NO_DATA
                },
                packetCount = mmWaveStats.packetCount,
                payloadBytes = mmWaveStats.payloadBytes,
                fileBytes = mmWaveStats.file?.takeIf { it.exists() }?.length(),
                malformedPackets = mmWaveStats.malformedPackets,
                droppedPackets = droppedMmWave,
                stateEventCount = mmWaveStats.stateEventCount,
                stateFileBytes = mmWaveStats.stateFile?.takeIf { it.exists() }?.length()
            )
        }
        manifest?.let { SessionManifestIO.write(dir, it) }
        reassembler = null
        sessionDir = null
        manifest = null
        if (options.mmWaveCaptureEnabled && mmWaveStats.packetCount == 0L &&
            mmWaveStats.malformedPackets == 0L && droppedMmWave == 0L &&
            !mmWaveFinalizeTimedOut
        ) {
            onLog("毫米波采集已启用，但本次未收到有效数据；无毫米波文件可导出")
        } else if (mmWaveStats.packetCount > 0L || mmWaveStats.malformedPackets > 0L ||
            droppedMmWave > 0L
        ) {
            onLog(
                "毫米波数据已保存 · ${mmWaveStats.packetCount} 包 · " +
                    "${mmWaveStats.payloadBytes} B" +
                    (if (mmWaveStats.malformedPackets > 0L) {
                        " · ${mmWaveStats.malformedPackets} 个格式错误"
                    } else "") +
                    (if (droppedMmWave > 0L) " · $droppedMmWave 个队列溢出" else "")
            )
        }
        onLog("录制已停止")
    }

    companion object {
        private const val MAX_PENDING_MMWAVE_PACKETS = 256
    }

    private fun maybeRotateSegment(packet: NunaProtocolInspector.AudioPacket?) {
        val duration = options.segmentDurationMs
        if (duration <= 0L) return

        val elapsed = (monotonicClockMs() - sessionStartMonotonicMs).coerceAtLeast(0L)
        val expectedTimeSlot = elapsed / duration
        if (currentSegmentTimeSlot >= expectedTimeSlot) return
        // Product Nuna groups six notifications under one frame ID. Rotate before chunk 0 so
        // the previous segment always closes after a complete group. Legacy/XIAO packets are
        // single-chunk groups and therefore continue to rotate immediately.
        if (packet != null && packet.totalChunks > 1 && packet.chunkId != 0) return

        // Only rotate once per incoming notification. If callbacks were delayed for several
        // segment windows, jump directly to the current window instead of creating empty files.
        closeCurrentSegment(enqueueVad = options.autoVadOnRecord)
        currentSegmentTimeSlot = expectedTimeSlot
        currentSegmentIndex = (manifest?.segments?.maxOfOrNull { it.index } ?: -1) + 1
        currentSegmentStartMs = expectedTimeSlot * duration
        openCurrentSegment()
    }

    private fun openCurrentSegment() {
        val dir = sessionDir ?: return
        SessionPaths.audioDir(dir).mkdirs()
        val rel = if (options.segmentEnabled) {
            SessionPaths.segmentRelativePath(currentSegmentIndex)
        } else {
            SessionPaths.STREAM_OPUS_FILE
        }
        val file = File(dir, rel)
        currentSegmentFile = file
        currentSegmentIntegrityIssue = null
        currentSegmentTimelineIssueLogged = false
        currentSegmentHasAudioPacket = false
        reassembler?.close()
        reassembler = BleAudioReassembler(file) { onLog(it) }
        flushManifestNow()
    }

    private fun closeCurrentSegment(
        enqueueVad: Boolean,
        allowIncompleteTail: Boolean = false
    ) {
        val dir = sessionDir ?: return
        val file = currentSegmentFile ?: return
        currentSegmentIntegrityIssue = reassembler?.close(allowIncompleteTail)
        reassembler?.consumeTrimmedTailDescription()?.let { detail ->
            val sessionOffsetMs = currentSessionOffsetMs()
            audioTimelineWriter.recordBoundaryEvent(
                event = "trimmed_incomplete_tail",
                detail = detail,
                receivedAtMs = sessionStartMs + sessionOffsetMs,
                sessionOffsetMs = sessionOffsetMs,
                segmentIndex = currentSegmentIndex
            )
        }
        if (currentSegmentIntegrityIssue != null && !currentSegmentTimelineIssueLogged) {
            val sessionOffsetMs = currentSessionOffsetMs()
            audioTimelineWriter.recordIntegrityEvent(
                reason = requireNotNull(currentSegmentIntegrityIssue),
                receivedAtMs = sessionStartMs + sessionOffsetMs,
                sessionOffsetMs = sessionOffsetMs,
                segmentIndex = currentSegmentIndex,
                phase = "segment_close"
            )
            currentSegmentTimelineIssueLogged = true
        }
        reassembler = null
        currentSegmentFile = null
        if (!file.exists() || file.length() == 0L) {
            file.delete()
            return
        }
        val bytes = file.length()
        val durationMs = bytes / 80 * 20L
        val maxDuration = if (options.segmentEnabled) options.segmentDurationMs else Long.MAX_VALUE
        val endMs = currentSegmentStartMs + durationMs.coerceAtMost(maxDuration)
        val rel = if (options.segmentEnabled) {
            SessionPaths.segmentRelativePath(currentSegmentIndex)
        } else {
            SessionPaths.STREAM_OPUS_FILE
        }
        val entry = AudioSegmentEntry(
            index = currentSegmentIndex,
            file = rel,
            startMs = currentSegmentStartMs,
            endMs = endMs,
            bytes = bytes,
            durationMs = durationMs.coerceAtMost(maxDuration),
            integrityOk = currentSegmentIntegrityIssue == null,
            integrityIssue = currentSegmentIntegrityIssue
        )
        manifest?.segments?.add(entry)
        SessionManifestIO.write(dir, manifest!!)
        onLog("分段 ${entry.index} 已保存 · ${formatSize(bytes)}")
        if (entry.integrityOk && enqueueVad && options.autoVadOnRecord) {
            VadJobQueue.enqueue(
                VadJob(
                    sessionDir = dir,
                    segmentIndex = entry.index,
                    opusFile = file,
                    audioRelPath = entry.file,
                    startMs = entry.startMs,
                    endMs = entry.endMs,
                    durationMs = entry.durationMs,
                    sessionStartedAtMs = sessionStartMs
                )
            )
        }
        if (!entry.integrityOk) {
            onLog("分段 ${entry.index} 检测到 BLE 缺口，已禁止自动上传")
        }
    }

    private fun maybeFlushManifest() {
        val now = monotonicClockMs()
        if (now - lastManifestFlushMs < 1000L) return
        flushManifestNow()
    }

    private fun flushManifestNow() {
        val dir = sessionDir ?: return
        val m = manifest ?: return
        lastManifestFlushMs = monotonicClockMs()
        m.recordingActive = true
        m.openSegmentIndex = currentSegmentIndex
        m.openSegmentBytes = currentSegmentFile?.takeIf { it.exists() }?.length() ?: 0L
        SessionManifestIO.write(dir, m)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun currentSessionOffsetMs(): Long =
        (monotonicClockMs() - sessionStartMonotonicMs).coerceAtLeast(0L)
}
