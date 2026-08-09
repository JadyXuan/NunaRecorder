package com.example.nunarecorder.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A002 上的录音开关命令（`CONTROL_REQUEST` + `SWITCH_RECORD`）。
 *
 * 来源：Ruihan 2026-08-09 从 Gavin 拿到的最新蓝牙协议，并已在产品版实机验证
 * （上游分支 `signed-test/recording-stop-recovery`，`docs/NUNA_PROTOCOL_VALIDATION.md`）。
 *
 * **为什么必须有它。** 老固件（XIAO 兼容）只要订阅 A003 就会推流，所以我们一直
 * 没发这条命令也能采到数据。产品版新固件不是这样：
 *
 * - 要**显式发 START** 才推流；
 * - 停止录制如果只是断开 GATT 而不发 STOP，**设备状态机会卡在录音态**，
 *   下次重连订阅上了也不响应——就是我们看到的"已订阅但没有收到数据"；
 * - 卡住之后先发一个 STOP 再发 START 就能恢复。
 *
 * 这解释了两件此前想不通的事：为什么必须放回充电板、长按进配对模式才能采到数据
 * （那会重置设备状态），以及为什么没升过固件的 04DF 一切正常。
 *
 * Ruihan 的原话："实际上发送 stop 也不会真的停止他的音频服务"——所以 STOP 更像是
 * 一次状态机复位，不要指望它能让设备真的静音。
 */
data class ControlResponse(
    val commandId: Int,
    val status: Int,
    val errorCode: Int?
)

object RecordingControlProtocol {
    const val START: Int = 0x01
    const val STOP: Int = 0x00

    /** `commandId(2, LE) | SWITCH_RECORD(0x03) | 0x01=开 0x00=关` */
    fun recordingCommandData(commandId: Int, enabled: Boolean): ByteArray {
        require(commandId in 0..0xFFFF) { "commandId 超出 2 字节范围: $commandId" }
        return ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putShort(commandId.toShort())
                put(ControlCommand.SWITCH_RECORD.value.toByte())
                put(if (enabled) START.toByte() else STOP.toByte())
            }
            .array()
    }

    fun parseControlResponse(data: ByteArray): ControlResponse? {
        if (data.size < 3) return null
        val commandId = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        return ControlResponse(
            commandId = commandId,
            status = data[2].toInt() and 0xFF,
            errorCode = data.getOrNull(3)?.toInt()?.and(0xFF)
        )
    }
}
