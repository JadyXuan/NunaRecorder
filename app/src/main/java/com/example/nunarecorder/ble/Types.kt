package com.example.nunarecorder.ble

/**
 * 外层 BLE 消息类型
 *
 * 对应外层协议中的 Type 字节：
 * | Header | Type | Length | Version | Checksum | Data... |
 */
enum class MessageType(val value: Int) {
    /** 握手相关消息 */
    HANDSHAKE(0x01),

    /** 控制请求（震动、录音开关、设置时间等） */
    CONTROL_REQUEST(0x06),

    /** 控制响应（可选，用于接收设备确认） */
    CONTROL_RESPONSE(0x07),
}

/**
 * 握手数据内部的子类型
 *
 * Data 部分的第 1 字节：
 * data[0] = HandshakeMessageType
 */
enum class HandshakeMessageType(val value: Int) {
    HANDSHAKE_REQUEST(0x01),
    HANDSHAKE_RESPONSE(0x02),
    HANDSHAKE_COMPLETED(0x03),
    HANDSHAKE_ERROR(0x04),
}


/**
 * 控制命令类型
 *
 * 作为 CONTROL_REQUEST 的 data[2]（命令头中的 "type" 字节）。
 *
 * 控制请求 Data 结构：
 *   data[0..1] = commandId (2B, LE)
 *   data[2]    = ControlCommand.value
 *   data[3..]  = payload（每种命令自己的参数）
 */
enum class ControlCommand(val value: Int) {
    /** 控制振动（start/stop/pause/continue + duration） */
    VIBRATE(0x01),

    /** 设置振动强度 (0-100) */
    SET_VIBRATION_INTENSITY(0x02),

    /** 开关录音 */
    SWITCH_RECORD(0x03),

    /** 设置设备时间（Unix 时间戳，毫秒，8 字节，小端） */
    SET_TIME(0x04),

    /** 毫米波雷达开关 */
    SWITCH_MILE_WAVE(0x05),

    /** 删除设备缓存数据 */
    DEL_CACHE_DATA(0x06),
}

/**
 * 振动控制子类型，用于 VIBRATE 命令的 payload[0]
 */
enum class VibrationControl(val value: Int) {
    /** 停止震动 */
    STOP(0x00),

    /** 开始震动 */
    START(0x01),

    /** 暂停震动 */
    PAUSE(0x02),

    /** 恢复震动 */
    RESUME(0x03),
}
