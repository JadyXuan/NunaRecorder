package com.example.nunarecorder.recording

import com.example.nunarecorder.ble.NunaProtocolInspector
import com.example.nunarecorder.session.SessionPaths
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AudioTimelineWriterTest {
    @Test
    fun writesPacketAndIntegrityEventOnSharedSessionTimeline() {
        val dir = Files.createTempDirectory("audio-timeline").toFile()
        try {
            val writer = AudioTimelineWriter()
            writer.start(dir)
            writer.recordPacket(
                packet = NunaProtocolInspector.AudioPacket(
                    frameId = 7,
                    frameSize = 80,
                    chunkId = 2,
                    totalChunks = 6,
                    timestampMs = 123_456L,
                    payloadBytes = 480,
                    opusFramesInPayload = 6,
                    payloadMatchesFrameSize = false
                ),
                receivedAtMs = 11_500L,
                sessionOffsetMs = 1_500L,
                segmentIndex = 0,
                segmentStartMs = 0L
            )
            writer.recordIntegrityEvent(
                reason = "expected 8, got 9",
                receivedAtMs = 12_000L,
                sessionOffsetMs = 2_000L,
                segmentIndex = 0,
                phase = "stream"
            )
            writer.stop()

            val rows = SessionPaths.audioTimelineFile(dir).readLines().map(::JSONObject)
            assertEquals(2, rows.size)
            assertEquals("audio_packet", rows[0].getString("type"))
            assertEquals(1_500L, rows[0].getLong("session_offset_ms"))
            assertEquals(123_456L, rows[0].getLong("device_timestamp_ms"))
            assertEquals(6, rows[0].getInt("opus_frames_in_payload"))
            assertEquals("audio_integrity_event", rows[1].getString("type"))
            assertTrue(rows[1].getString("reason").contains("got 9"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
