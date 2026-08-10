package com.example.nunarecorder.recording

import com.example.nunarecorder.ble.NunaProtocolInspector
import com.example.nunarecorder.session.SessionPaths
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * Sidecar timeline for correlating raw Opus frames with other session modalities.
 *
 * One row is written for every A003 packet accepted by [SessionRecorder]. Device and
 * phone-monotonic-derived timestamps are retained because their exact protocol semantics
 * can be refined later without changing the raw Opus format.
 */
class AudioTimelineWriter {
    private var sessionDir: File? = null
    private var writer: BufferedWriter? = null
    private var packetIndex = 0L
    private var packetsSinceFlush = 0
    private var lastFlushOffsetMs = 0L

    @Synchronized
    fun start(dir: File) {
        stop()
        sessionDir = dir
    }

    @Synchronized
    fun recordPacket(
        packet: NunaProtocolInspector.AudioPacket,
        receivedAtMs: Long,
        sessionOffsetMs: Long,
        segmentIndex: Int,
        segmentStartMs: Long
    ) {
        ensureWriter()
        val row = JSONObject().apply {
            put("type", "audio_packet")
            put("format_version", 1)
            put("packet_index", packetIndex++)
            put("received_at_ms", receivedAtMs)
            put("session_offset_ms", sessionOffsetMs)
            put("segment_index", segmentIndex)
            put("segment_offset_ms", (sessionOffsetMs - segmentStartMs).coerceAtLeast(0L))
            put("device_timestamp_ms", packet.timestampMs)
            put("frame_id", packet.frameId)
            put("chunk_id", packet.chunkId)
            put("total_chunks", packet.totalChunks)
            put("frame_size_bytes", packet.frameSize)
            put("payload_bytes", packet.payloadBytes)
            packet.opusFramesInPayload?.let { put("opus_frames_in_payload", it) }
        }
        write(row, sessionOffsetMs)
    }

    @Synchronized
    fun recordIntegrityEvent(
        reason: String,
        receivedAtMs: Long,
        sessionOffsetMs: Long,
        segmentIndex: Int,
        phase: String
    ) {
        ensureWriter()
        val row = JSONObject().apply {
            put("type", "audio_integrity_event")
            put("format_version", 1)
            put("received_at_ms", receivedAtMs)
            put("session_offset_ms", sessionOffsetMs)
            put("segment_index", segmentIndex)
            put("phase", phase)
            put("reason", reason)
        }
        write(row, sessionOffsetMs)
    }

    @Synchronized
    fun stop(): File? {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
        val result = sessionDir?.let(SessionPaths::audioTimelineFile)?.takeIf { it.isFile }
        sessionDir = null
        packetIndex = 0L
        packetsSinceFlush = 0
        lastFlushOffsetMs = 0L
        return result
    }

    private fun ensureWriter() {
        if (writer != null) return
        val target = SessionPaths.audioTimelineFile(requireNotNull(sessionDir))
        target.parentFile?.mkdirs()
        writer = BufferedWriter(
            OutputStreamWriter(FileOutputStream(target, false), Charsets.UTF_8),
            32 * 1024
        )
    }

    private fun write(row: JSONObject, sessionOffsetMs: Long) {
        writer!!.apply {
            write(row.toString())
            newLine()
        }
        packetsSinceFlush++
        if (packetsSinceFlush >= 16 || sessionOffsetMs - lastFlushOffsetMs >= 1_000L) {
            writer?.flush()
            packetsSinceFlush = 0
            lastFlushOffsetMs = sessionOffsetMs
        }
    }
}
