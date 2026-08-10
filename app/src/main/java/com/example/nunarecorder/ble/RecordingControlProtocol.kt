package com.example.nunarecorder.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ControlResponse(
    val commandId: Int,
    val status: Int,
    val errorCode: Int?
)

/** Wire helpers for Nuna CONTROL_REQUEST / CONTROL_RESPONSE payloads. */
object RecordingControlProtocol {
    const val START: Int = 0x01
    const val STOP: Int = 0x00

    fun recordingCommandData(commandId: Int, enabled: Boolean): ByteArray {
        return booleanCommandData(commandId, ControlCommand.SWITCH_RECORD, enabled)
    }

    fun radarCommandData(commandId: Int, enabled: Boolean): ByteArray {
        return booleanCommandData(commandId, ControlCommand.SWITCH_MILE_WAVE, enabled)
    }

    private fun booleanCommandData(
        commandId: Int,
        command: ControlCommand,
        enabled: Boolean
    ): ByteArray {
        require(commandId in 0..0xFFFF)
        return ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putShort(commandId.toShort())
                put(command.value.toByte())
                put(if (enabled) START.toByte() else STOP.toByte())
            }
            .array()
    }

    fun parseControlResponse(data: ByteArray): ControlResponse? {
        if (data.size < 3) return null
        val commandId = (data[0].toInt() and 0xFF) or
            ((data[1].toInt() and 0xFF) shl 8)
        return ControlResponse(
            commandId = commandId,
            status = data[2].toInt() and 0xFF,
            errorCode = data.getOrNull(3)?.toInt()?.and(0xFF)
        )
    }
}
