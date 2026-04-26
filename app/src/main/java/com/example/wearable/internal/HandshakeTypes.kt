package com.example.wearable.internal

/**
 * 外层 BLE 消息类型（与 Nuna 设备协议一致）。
 */
enum class MessageType(val value: Int) {
    HANDSHAKE(0x01),
    CONTROL_REQUEST(0x06),
    CONTROL_RESPONSE(0x07),
}

/**
 * 握手数据内层子类型。
 */
enum class HandshakeMessageType(val value: Int) {
    HANDSHAKE_REQUEST(0x01),
    HANDSHAKE_RESPONSE(0x02),
    HANDSHAKE_COMPLETED(0x03),
    HANDSHAKE_ERROR(0x04),
}

/**
 * 控制命令类型（CONTROL_REQUEST 的 data[2]）。
 */
enum class ControlCommand(val value: Int) {
    SET_TIME(0x04),
}
