package com.example.wearable.internal

import java.util.UUID

/**
 * 可穿戴 BLE 服务与协议常量（与 Nuna 设备协议一致，便于即插即用，不依赖 nunarecorder）。
 */
object WearableBleConfig {

    /**
     * 代码配置的固定连接设备 MAC（调试用；与 NunaRecorder 主流程无关）。
     * 临时页面与默认连接均使用此地址。
     */
    const val DEFAULT_DEVICE_ADDRESS: String = "4C:FF:01:A0:05:4E"

    const val HEADER_MAGIC: Int = 0xAA
    const val AUDIO_TYPE: Int = 0x10
    const val MIN_HEADER_LEN: Int = 7
    const val MIN_AUDIO_BIZ_HEADER: Int = 14

    val SERVICE_UUID: UUID get() = UUID.fromString("0000A000-0000-1000-8000-00805F9B34FB")
    /** 状态特征 A001，用于 triggerDeviceStart 的 read 请求 */
    val STATUS_CHAR_UUID: UUID get() = UUID.fromString("0000A001-0000-1000-8000-00805F9B34FB")
    /** 双向传输特征 A002，握手与控制命令 */
    val TRANSFER_CHAR_UUID: UUID get() = UUID.fromString("0000A002-0000-1000-8000-00805F9B34FB")
    val RECORDING_CHAR_UUID: UUID get() = UUID.fromString("0000A003-0000-1000-8000-00805F9B34FB")
    val CCCD_UUID: UUID get() = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    /** 外层消息头，用于握手/控制包 */
    const val MESSAGE_HEADER: Int = 0xAA
    const val MESSAGE_VERSION: Int = 0x01

    const val OPUS_FRAME_DURATION_MS: Long = 20L
    const val OPUS_FRAME_SIZE_BYTES: Int = 80
    const val SAMPLE_RATE: Int = 16000
    const val OPUS_CHANNELS: Int = 2
}
