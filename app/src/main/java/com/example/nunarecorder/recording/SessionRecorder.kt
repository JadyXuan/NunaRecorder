package com.example.nunarecorder.recording

import android.os.SystemClock
import com.example.nunarecorder.ble.BleAudioReassembler
import com.example.nunarecorder.session.AudioSegmentEntry
import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionManifestIO
import com.example.nunarecorder.session.SessionPaths
import com.example.nunarecorder.session.VadSummary
import com.example.nunarecorder.vad.VadJob
import com.example.nunarecorder.vad.VadJobQueue
import com.example.nunarecorder.vad.VadPrelabelWriter
import java.io.File

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
    private var sessionStartMs = 0L
    private var sessionStartMonotonicMs = 0L
    private var options: RecordingOptions = RecordingOptions(
        segmentEnabled = true,
        segmentDurationMs = SessionPaths.SEGMENT_DURATION_MS,
        autoVadOnRecord = true
    )
    private var lastManifestFlushMs = 0L

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
            recordingActive = true
        )
        SessionManifestIO.write(dir, manifest!!)
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
        if (options.segmentEnabled) {
            maybeRotateSegment()
        }
        val activeReassembler = reassembler
        activeReassembler?.feed(data)
        val newIntegrityIssue = activeReassembler?.consumeNewIntegrityIssue()
        maybeFlushManifest()
        return newIntegrityIssue
    }

    fun stop() {
        val dir = sessionDir ?: return
        closeCurrentSegment(enqueueVad = options.autoVadOnRecord)
        manifest?.endedAtMs = wallClockMs()
        manifest?.recordingActive = false
        manifest?.openSegmentIndex = null
        manifest?.openSegmentBytes = 0L
        manifest?.let { SessionManifestIO.write(dir, it) }
        reassembler = null
        sessionDir = null
        manifest = null
        onLog("录制已停止")
    }

    private fun maybeRotateSegment() {
        val duration = options.segmentDurationMs
        if (duration <= 0L) return

        val elapsed = (monotonicClockMs() - sessionStartMonotonicMs).coerceAtLeast(0L)
        val expectedTimeSlot = elapsed / duration
        if (currentSegmentTimeSlot >= expectedTimeSlot) return

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
        reassembler?.close()
        reassembler = BleAudioReassembler(file) { onLog(it) }
        flushManifestNow()
    }

    private fun closeCurrentSegment(enqueueVad: Boolean) {
        val dir = sessionDir ?: return
        val file = currentSegmentFile ?: return
        currentSegmentIntegrityIssue = reassembler?.close()
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
}
