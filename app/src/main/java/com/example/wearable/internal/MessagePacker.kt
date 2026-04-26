package com.example.wearable.internal

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BLE 外层消息封装与解析（与 Nuna 设备协议一致）。
 * 格式: | Header(1B=0xAA) | Type(1B) | Length(2B,LE) | Version(1B) | Checksum(2B,LE) | Data(nB) |
 */
object MessagePacker {

    fun pack(type: MessageType, data: ByteArray): ByteArray {
        val message = ByteArray(7 + data.size)
        message[0] = WearableBleConfig.MESSAGE_HEADER.toByte()
        message[1] = type.value.toByte()
        ByteBuffer.wrap(message, 2, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(data.size.toShort())
        message[4] = WearableBleConfig.MESSAGE_VERSION.toByte()
        ByteBuffer.wrap(message, 5, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(0x1234)
        System.arraycopy(data, 0, message, 7, data.size)
        return message
    }

    fun unpack(raw: ByteArray): Pair<MessageType, ByteArray>? {
        if (raw.size < 7) return null
        if (raw[0].toInt() and 0xFF != WearableBleConfig.MESSAGE_HEADER) return null
        val typeValue = raw[1].toInt() and 0xFF
        val length = (raw[2].toInt() and 0xFF) or ((raw[3].toInt() and 0xFF) shl 8)
        if (raw.size < 7 + length) return null
        val type = MessageType.entries.find { it.value == typeValue } ?: return null
        return type to raw.copyOfRange(7, 7 + length)
    }
}
