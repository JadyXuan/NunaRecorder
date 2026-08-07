package com.example.nunarecorder.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BleAudioReassemblerTest {

    @Test
    fun marksNonConsecutiveFrameIdsAsIntegrityFailure() {
        val output = Files.createTempFile("nuna-gap", ".opus").toFile()
        try {
            val reassembler = BleAudioReassembler(output)

            reassembler.feed(audioNotification(frameId = 10))
            assertTrue(reassembler.integrityOk)
            reassembler.feed(audioNotification(frameId = 12))

            assertFalse(reassembler.integrityOk)
            assertTrue(reassembler.consumeNewIntegrityIssue()?.contains("期望 11") == true)
            assertNull(reassembler.consumeNewIntegrityIssue())
            assertTrue(reassembler.close()?.contains("期望 11") == true)
        } finally {
            output.delete()
        }
    }

    @Test
    fun acceptsFrameIdWraparound() {
        val output = Files.createTempFile("nuna-wrap", ".opus").toFile()
        try {
            val reassembler = BleAudioReassembler(output)

            reassembler.feed(audioNotification(frameId = 0xFFFF))
            reassembler.feed(audioNotification(frameId = 0))

            assertTrue(reassembler.integrityOk)
            assertTrue(reassembler.close() == null)
        } finally {
            output.delete()
        }
    }

    private fun audioNotification(frameId: Int): ByteArray {
        val payloadLength = 14 + 80
        return ByteArray(7 + payloadLength).apply {
            this[0] = 0xAA.toByte()
            this[1] = 0x10
            putLe16(2, payloadLength)
            this[4] = 0x01
            this[5] = 0x34
            this[6] = 0x12
            putLe16(7, frameId)
            putLe16(9, 80)
            this[11] = 0
            this[12] = 1
            for (index in 0 until 80) {
                this[21 + index] = index.toByte()
            }
        }
    }

    private fun ByteArray.putLe16(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
