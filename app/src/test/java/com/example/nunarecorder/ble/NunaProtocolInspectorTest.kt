package com.example.nunarecorder.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NunaProtocolInspectorTest {
    @Test
    fun crc16XmodemMatchesCanonicalVector() {
        assertEquals(0x31C3, NunaProtocolInspector.crc16Xmodem("123456789".toByteArray()))
    }

    @Test
    fun parsesLegacyEnvelopeWithoutEnforcingChecksum() {
        val raw = byteArrayOf(
            0xAA.toByte(), 0x03, 0x01, 0x00, 0x01, 0x34, 0x12, 87
        )

        val envelope = requireNotNull(NunaProtocolInspector.parseEnvelope(raw))

        assertEquals(0x03, envelope.type)
        assertEquals(1, envelope.declaredDataLength)
        assertEquals(0x1234, envelope.checksum)
        assertEquals("legacy_fixed_1234", envelope.checksumClassification)
        assertEquals(87, NunaProtocolInspector.parseStatus(envelope).fields["percent"])
    }

    @Test
    fun identifiesDataOnlyXmodemChecksum() {
        val data = byteArrayOf(42)
        val crc = NunaProtocolInspector.crc16Xmodem(data)
        val raw = byteArrayOf(
            0xAA.toByte(), 0x03, 0x01, 0x00, 0x01,
            (crc and 0xFF).toByte(), (crc ushr 8).toByte(), data[0]
        )

        val envelope = requireNotNull(NunaProtocolInspector.parseEnvelope(raw))

        assertEquals("crc16_xmodem_data", envelope.checksumClassification)
    }

    @Test
    fun parsesBatchedAudioHeader() {
        val payload = ByteArray(480) { it.toByte() }
        val data = byteArrayOf(
            0x34, 0x12, 0x50, 0x00, 0x00, 0x01,
            0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01
        ) + payload
        val raw = byteArrayOf(
            0xAA.toByte(), 0x10, 0xEE.toByte(), 0x01, 0x01, 0x34, 0x12
        ) + data

        val audio = requireNotNull(NunaProtocolInspector.parseAudio(raw))

        assertEquals(0x1234, audio.frameId)
        assertEquals(80, audio.frameSize)
        assertEquals(480, audio.payloadBytes)
        assertEquals(6, audio.opusFramesInPayload)
        assertFalse(audio.payloadMatchesFrameSize)
        assertEquals(0x0102030405060708L, audio.timestampMs)
    }

    @Test
    fun parsesDeviceInfoAndRejectsTruncatedEnvelope() {
        val data = ByteArray(58)
        "NUNA-PRODUCT".toByteArray().copyInto(data, 0)
        byteArrayOf(1, 2, 3, 4, 5, 6).copyInto(data, 16)
        "1.4.4".toByteArray().copyInto(data, 22)
        "HW2".toByteArray().copyInto(data, 34)
        "SN123".toByteArray().copyInto(data, 42)
        val raw = byteArrayOf(
            0xAA.toByte(), 0x05, 58, 0, 1, 0x34, 0x12
        ) + data

        val status = NunaProtocolInspector.parseStatus(
            requireNotNull(NunaProtocolInspector.parseEnvelope(raw))
        )

        assertEquals("NUNA-PRODUCT", status.fields["model"])
        assertEquals("01:02:03:04:05:06", status.fields["mac"])
        assertEquals("1.4.4", status.fields["firmware"])
        assertTrue(status.fields["payload_bytes"] == 58)
        assertNull(NunaProtocolInspector.parseEnvelope(raw.copyOf(raw.size - 1)))
    }
}
