package com.example.nunarecorder.recording

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

/**
 * 会话录制：按墙钟 60s 轮转 Opus 段；段封口后入队 Silero VAD（即时标注）。
 */
class SessionRecorder(
    private val onLog: (String) -> Unit
) {
    private var sessionDir: File? = null
    private var manifest: SessionManifest? = null
    private var reassembler: BleAudioReassembler? = null
    private var currentSegmentIndex = 0
    private var currentSegmentFile: File? = null
    private var currentSegmentStartMs = 0L
    private var sessionStartMs = 0L

    val activeSessionDir: File? get() = sessionDir

    fun start(deviceName: String, deviceAddress: String?) {
        stop()
        sessionStartMs = System.currentTimeMillis()
        val dir = SessionPaths.newSessionDir(deviceName, sessionStartMs)
        sessionDir = dir
        manifest = SessionManifest(
            sessionId = dir.name,
            deviceName = deviceName,
            deviceAddress = deviceAddress,
            startedAtMs = sessionStartMs,
            vad = VadSummary(status = "pending")
        )
        SessionManifestIO.write(dir, manifest!!)
        VadPrelabelWriter.markRunning(dir, 0, sessionStartMs)
        openNextSegment()
        onLog("Session recording started: ${dir.absolutePath}")
    }

    fun feed(data: ByteArray) {
        maybeRotateSegment()
        reassembler?.feed(data)
    }

    fun stop() {
        val dir = sessionDir ?: return
        closeCurrentSegment(enqueueVad = true)
        manifest?.endedAtMs = System.currentTimeMillis()
        manifest?.let { SessionManifestIO.write(dir, it) }
        reassembler = null
        sessionDir = null
        manifest = null
        onLog("Session recording stopped.")
    }

    private fun maybeRotateSegment() {
        val dir = sessionDir ?: return
        val elapsed = System.currentTimeMillis() - sessionStartMs
        val expectedIndex = (elapsed / SessionPaths.SEGMENT_DURATION_MS).toInt()
        while (currentSegmentIndex < expectedIndex) {
            closeCurrentSegment(enqueueVad = true)
            openNextSegment()
        }
    }

    private fun openNextSegment() {
        val dir = sessionDir ?: return
        SessionPaths.audioDir(dir).mkdirs()
        currentSegmentIndex = manifest?.segments?.size ?: 0
        currentSegmentStartMs = currentSegmentIndex * SessionPaths.SEGMENT_DURATION_MS
        val rel = SessionPaths.segmentRelativePath(currentSegmentIndex)
        val file = File(dir, rel)
        currentSegmentFile = file
        reassembler?.close()
        reassembler = BleAudioReassembler(file) { onLog(it) }
        onLog("Opened segment $currentSegmentIndex: ${file.name}")
    }

    private fun closeCurrentSegment(enqueueVad: Boolean) {
        val dir = sessionDir ?: return
        val file = currentSegmentFile ?: return
        reassembler?.close()
        reassembler = null
        if (!file.exists() || file.length() == 0L) {
            file.delete()
            return
        }
        val bytes = file.length()
        val durationMs = bytes / 80 * 20L // 80 bytes/frame, 20ms/frame
        val endMs = currentSegmentStartMs + durationMs.coerceAtMost(SessionPaths.SEGMENT_DURATION_MS)
        val entry = AudioSegmentEntry(
            index = currentSegmentIndex,
            file = SessionPaths.segmentRelativePath(currentSegmentIndex),
            startMs = currentSegmentStartMs,
            endMs = endMs,
            bytes = bytes,
            durationMs = durationMs.coerceAtMost(SessionPaths.SEGMENT_DURATION_MS)
        )
        manifest?.segments?.add(entry)
        SessionManifestIO.write(dir, manifest!!)
        onLog("Closed segment ${entry.index}: ${bytes} bytes, ~${durationMs}ms")
        if (enqueueVad) {
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
        currentSegmentFile = null
    }
}
