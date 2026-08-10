package com.example.nunarecorder.recording

import com.example.nunarecorder.session.SessionPaths
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Base64

class MmWaveSessionWriterTest {
    @Test
    fun writesLosslessJsonlOnlyAfterFirstSensorPacket() {
        val dir = Files.createTempDirectory("mmwave-session").toFile()
        var now = 2_000L
        val writer = MmWaveSessionWriter { now }
        writer.start(dir, 1_000L)

        assertFalse(SessionPaths.mmWaveFile(dir).exists())
        assertEquals(MmWaveCaptureResult.NotSensorData, writer.feed(byteArrayOf(1, 2, 3)))

        val payload = byteArrayOf(0x55, 0x66, 0x77)
        val data = byteArrayOf(
            0x01,
            0xD2.toByte(), 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        ) + payload
        val raw = byteArrayOf(
            0xAA.toByte(), 0x08, data.size.toByte(), 0x00, 0x01, 0x34, 0x12
        ) + data

        assertEquals(MmWaveCaptureResult.Captured(0, payload.size), writer.feed(raw))
        writer.recordState(
            enabled = false,
            requestedByApp = true,
            receivedAtMs = 2_500L,
            sessionOffsetMs = 1_500L,
            source = "a001_0x13"
        )
        val stats = writer.stop()

        assertEquals(1L, stats.packetCount)
        assertEquals(payload.size.toLong(), stats.payloadBytes)
        assertEquals(1L, stats.stateEventCount)
        val row = JSONObject(SessionPaths.mmWaveFile(dir).readLines().single())
        assertEquals("mmwave_raw", row.getString("type"))
        assertEquals(1_000L, row.getLong("session_offset_ms"))
        assertEquals(1_234L, row.getLong("device_timestamp_ms"))
        assertTrue(payload.contentEquals(Base64.getDecoder().decode(row.getString("payload_base64"))))
        assertTrue(raw.contentEquals(Base64.getDecoder().decode(row.getString("raw_packet_base64"))))
        val state = JSONObject(SessionPaths.mmWaveStateFile(dir).readLines().single())
        assertEquals("mmwave_state", state.getString("type"))
        assertEquals("sleeping", state.getString("state"))
        assertEquals(1_500L, state.getLong("session_offset_ms"))
    }

    @Test
    fun recordsMalformedType08WithoutCreatingAFile() {
        val dir = Files.createTempDirectory("mmwave-malformed").toFile()
        val writer = MmWaveSessionWriter { 2_000L }
        writer.start(dir, 1_000L)
        val malformed = byteArrayOf(
            0xAA.toByte(), 0x08, 0x01, 0x00, 0x01, 0x34, 0x12, 0x01
        )

        assertEquals(MmWaveCaptureResult.Malformed, writer.feed(malformed))
        val stats = writer.stop()

        assertEquals(1L, stats.malformedPackets)
        assertFalse(SessionPaths.mmWaveFile(dir).exists())
    }
}
