package com.example.nunarecorder.ble

/**
 * BLE 协议基础配置（精简版）
 * 仅包含握手与控制命令所需的常量
 */
object ProtoConfig {

    /**
     * 服务和特征 UUID
     */
    object Service {
        /** 广播服务 UUID */
        const val SERVICE_UUID = "0000A000-0000-1000-8000-00805F9B34FB"

        /** 状态特征（READ/NOTIFY），设备信息 / 状态上报等 */
        const val STATUS_CHAR_UUID = "0000A001-0000-1000-8000-00805F9B34FB"

        /**
         * 双向传输特征（READ/WRITE/INDICATE）
         * 握手认证 + 控制命令 都走这个特征
         */
        const val TRANSFER_CHAR_UUID = "0000A002-0000-1000-8000-00805F9B34FB"

        /** 实时音频传输特征（WRITE/INDICATE），音频数据 */
        const val RECORDING_CHAR_UUID = "0000A003-0000-1000-8000-00805F9B34FB"

        /** 客户端特征配置描述符 UUID（用于开启 Notify/Indicate） */
        const val CCCD_UUID = "00002902-0000-1000-8000-00805F9B34FB"
    }

    /**
     * 协议版本
     */
    object Version {
        const val CURRENT: Int = 0x01
    }



    /**
     * 消息格式相关常量
     *
     * 外层消息格式:
     * | Header(1B) | Type(1B) | Length(2B,LE) | Version(1B) | Checksum(2B,LE) | Data(nB) |
     */
    object Message {
        /** 固定消息头 */
        const val HEADER: Int = 0xAA

        /** 最大消息长度（含头） */
        const val MAX_LENGTH: Int = 512

        /** 头部长度（Header+Type+Length+Version）= 1+1+2+1 */
        const val HEADER_LENGTH: Int = 4

        /** 校验和长度 */
        const val CHECKSUM_LENGTH: Int = 2
    }
}
