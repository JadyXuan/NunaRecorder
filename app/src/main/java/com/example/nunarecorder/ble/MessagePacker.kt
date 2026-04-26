package com.example.nunarecorder.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 负责 BLE 外层消息的封装与解析
 *
 * 外层消息格式:
 * | Header(1B=0xAA) | Type(1B) | Length(2B,LE) | Version(1B) | Checksum(2B,LE) | Data(nB) |
 */
object MessagePacker {

    /**
     * 封装一条消息
     *
     * @param type 外层消息类型（MessageType）
     * @param data 内层数据（握手请求 / 控制命令等）
     */
    fun pack(type: MessageType, data: ByteArray): ByteArray {
        // 总长度 = 7 字节头 + data 长度
        val message = ByteArray(7 + data.size)

        // Header
        message[0] = ProtoConfig.Message.HEADER.toByte()

        // Type
        message[1] = type.value.toByte()

        // Length（Data 长度，2 字节，小端）
        ByteBuffer
            .wrap(message, 2, 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(data.size.toShort())

        // Version
        message[4] = ProtoConfig.Version.CURRENT.toByte()

        // 校验和（目前协议写死 0x1234，小端）
        val checksum = 0x1234
        ByteBuffer
            .wrap(message, 5, 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(checksum.toShort())

        // 拷贝数据部分
        System.arraycopy(data, 0, message, 7, data.size)

        return message
    }

    /**
     * 尝试解析一条完整消息，返回 (type, data)
     *
     * @return Pair<MessageType, ByteArray> 或 null（解析失败）
     */
    fun unpack(raw: ByteArray): Pair<MessageType, ByteArray>? {
        if (raw.size < 7) return null
        if (raw[0].toInt() and 0xFF != ProtoConfig.Message.HEADER) return null

        val typeValue = raw[1].toInt() and 0xFF
        val length = ((raw[2].toInt() and 0xFF)
                or ((raw[3].toInt() and 0xFF) shl 8))

        if (raw.size < 7 + length) return null

        val type = MessageType.values().find { it.value == typeValue } ?: return null

        val data = raw.copyOfRange(7, 7 + length)
        return type to data
    }
}
