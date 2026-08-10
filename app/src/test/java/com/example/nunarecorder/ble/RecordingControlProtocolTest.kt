package com.example.nunarecorder.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingControlProtocolTest {
    @Test
    fun startCommandUsesLittleEndianIdAndOfficialRecordingOpcode() {
        val data = RecordingControlProtocol.recordingCommandData(0x1234, enabled = true)
        assertArrayEquals(
            byteArrayOf(0x34, 0x12, 0x03, 0x01),
            data
        )
        assertArrayEquals(
            byteArrayOf(
                0xAA.toByte(), 0x06, 0x04, 0x00, 0x01, 0x34, 0x12,
                0x34, 0x12, 0x03, 0x01
            ),
            MessagePacker.pack(MessageType.CONTROL_REQUEST, data)
        )
    }

    @Test
    fun stopCommandUsesZeroPayload() {
        assertArrayEquals(
            byteArrayOf(0x01, 0x00, 0x03, 0x00),
            RecordingControlProtocol.recordingCommandData(1, enabled = false)
        )
    }

    @Test
    fun radarCommandsUseOfficialOpcodeAndBooleanPayload() {
        assertArrayEquals(
            byteArrayOf(0x34, 0x12, 0x05, 0x01),
            RecordingControlProtocol.radarCommandData(0x1234, enabled = true)
        )
        assertArrayEquals(
            byteArrayOf(0x35, 0x12, 0x05, 0x00),
            RecordingControlProtocol.radarCommandData(0x1235, enabled = false)
        )
        assertArrayEquals(
            byteArrayOf(
                0xAA.toByte(), 0x06, 0x04, 0x00, 0x01, 0x34, 0x12,
                0x34, 0x12, 0x05, 0x01
            ),
            MessagePacker.pack(
                MessageType.CONTROL_REQUEST,
                RecordingControlProtocol.radarCommandData(0x1234, enabled = true)
            )
        )
    }

    @Test
    fun parsesProductFourByteControlResponse() {
        val response = RecordingControlProtocol.parseControlResponse(
            byteArrayOf(0x34, 0x12, 0x01, 0x04)
        )

        assertEquals(0x1234, response?.commandId)
        assertEquals(1, response?.status)
        assertEquals(4, response?.errorCode)
    }

    @Test
    fun parsesLegacyThreeByteControlResponse() {
        val response = RecordingControlProtocol.parseControlResponse(
            byteArrayOf(0x02, 0x00, 0x00)
        )

        assertEquals(2, response?.commandId)
        assertEquals(0, response?.status)
        assertNull(response?.errorCode)
        assertNull(RecordingControlProtocol.parseControlResponse(byteArrayOf(1, 2)))
    }
}
