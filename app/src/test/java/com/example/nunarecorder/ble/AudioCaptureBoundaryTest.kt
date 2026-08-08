package com.example.nunarecorder.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCaptureBoundaryTest {
    private val boundary = AudioCaptureBoundary()

    @Test
    fun skipsMidGroupPacketsUntilChunkZero() {
        assertFalse(boundary.onPacket(packet(chunk = 4)).writePacket)
        assertFalse(boundary.onPacket(packet(chunk = 5)).writePacket)

        val start = boundary.onPacket(packet(chunk = 0))

        assertTrue(start.writePacket)
        assertTrue(boundary.aligned)
        assertEquals(2, boundary.skippedLeadingPackets)
    }

    @Test
    fun reportsBoundaryOnlyAfterFinalWrittenChunk() {
        boundary.onPacket(packet(chunk = 0))
        for (chunk in 1..4) {
            val decision = boundary.onPacket(packet(chunk = chunk))
            assertTrue(decision.writePacket)
            assertFalse(decision.groupEndAfterWrite)
        }

        val end = boundary.onPacket(packet(chunk = 5))

        assertTrue(end.writePacket)
        assertTrue(end.groupEndAfterWrite)
        assertTrue(boundary.lastWrittenPacketEndedGroup)
    }

    @Test
    fun singleChunkAndUnknownLegacyPacketsRemainCompatible() {
        val single = boundary.onPacket(packet(chunk = 0, total = 1))
        assertTrue(single.writePacket)
        assertTrue(single.groupEndAfterWrite)

        boundary.reset()
        val unknown = boundary.onPacket(null)
        assertTrue(unknown.writePacket)
        assertFalse(unknown.groupEndAfterWrite)
        assertTrue(boundary.aligned)
    }

    private fun packet(chunk: Int, total: Int = 6) = NunaProtocolInspector.AudioPacket(
        frameId = 10,
        frameSize = 80,
        chunkId = chunk,
        totalChunks = total,
        timestampMs = 1234L,
        payloadBytes = 480,
        opusFramesInPayload = 6,
        payloadMatchesFrameSize = false
    )
}
