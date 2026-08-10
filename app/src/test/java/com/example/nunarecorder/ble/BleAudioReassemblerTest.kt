package com.example.nunarecorder.ble

import org.junit.Assert.assertEquals
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

    @Test
    fun acceptsXiaoSingleOpusFrameGroups() {
        val output = Files.createTempFile("nuna-xiao-single", ".opus").toFile()
        try {
            val reassembler = BleAudioReassembler(output)

            reassembler.feed(audioNotification(frameId = 100))
            reassembler.feed(audioNotification(frameId = 101))

            assertTrue(reassembler.integrityOk)
            assertNull(reassembler.close())
            assertEquals(160L, output.length())
        } finally {
            output.delete()
        }
    }

    @Test
    fun acceptsProductNunaBatchedOpusFrames() {
        val output = Files.createTempFile("nuna-product-batch", ".opus").toFile()
        try {
            val reassembler = BleAudioReassembler(output)

            repeat(6) { chunkId ->
                reassembler.feed(
                    audioNotification(
                        frameId = 51,
                        chunkId = chunkId,
                        totalChunks = 6,
                        opusBytes = ByteArray(480) { (chunkId + it).toByte() }
                    )
                )
            }

            assertTrue(reassembler.integrityOk)
            assertNull(reassembler.consumeNewIntegrityIssue())
            assertNull(reassembler.close())
            assertEquals(2_880L, output.length())
        } finally {
            output.delete()
        }
    }

    @Test
    fun keepsCompleteOpusFramesWhenBatchHasTrailingBytes() {
        val output = Files.createTempFile("nuna-product-tail", ".opus").toFile()
        try {
            val reassembler = BleAudioReassembler(output)

            reassembler.feed(
                audioNotification(
                    frameId = 7,
                    opusBytes = ByteArray(161) { it.toByte() }
                )
            )

            assertFalse(reassembler.integrityOk)
            assertTrue(reassembler.consumeNewIntegrityIssue()?.contains("尾部 1 B") == true)
            reassembler.close()
            assertEquals(160L, output.length())
        } finally {
            output.delete()
        }
    }

    @Test
    fun intentionalStopCanTrimOnlyTheIncompleteTrailingGroup() {
        val output = Files.createTempFile("nuna-stop-tail", ".opus").toFile()
        try {
            val reassembler = BleAudioReassembler(output)
            reassembler.feed(audioNotification(frameId = 1))
            reassembler.feed(
                audioNotification(
                    frameId = 2,
                    chunkId = 0,
                    totalChunks = 6,
                    opusBytes = ByteArray(480)
                )
            )

            assertNull(reassembler.close(allowIncompleteTail = true))
            assertTrue(reassembler.integrityOk)
            assertTrue(reassembler.consumeTrimmedTailDescription()?.contains("丢弃 1 个") == true)
            assertEquals(80L, output.length())
        } finally {
            output.delete()
        }
    }

    private fun audioNotification(
        frameId: Int,
        chunkId: Int = 0,
        totalChunks: Int = 1,
        opusBytes: ByteArray = ByteArray(80) { it.toByte() }
    ): ByteArray {
        val payloadLength = 14 + opusBytes.size
        return ByteArray(7 + payloadLength).apply {
            this[0] = 0xAA.toByte()
            this[1] = 0x10
            putLe16(2, payloadLength)
            this[4] = 0x01
            this[5] = 0x34
            this[6] = 0x12
            putLe16(7, frameId)
            putLe16(9, 80)
            this[11] = chunkId.toByte()
            this[12] = totalChunks.toByte()
            opusBytes.copyInto(this, destinationOffset = 21)
        }
    }

    private fun ByteArray.putLe16(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
