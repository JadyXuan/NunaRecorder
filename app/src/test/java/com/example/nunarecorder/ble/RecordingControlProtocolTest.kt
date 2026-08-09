package com.example.nunarecorder.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 产品版固件的录音开关。字节错一位设备就不动，而它不会报错——
 * 表现是"已订阅但没有收到数据"，和固件卡死一模一样，分不出来。
 */
class RecordingControlProtocolTest {

    @Test
    fun `START 的字节序：commandId 小端 + 0x03 + 0x01`() {
        val d = RecordingControlProtocol.recordingCommandData(0x1234, enabled = true)
        assertEquals(4, d.size)
        assertEquals(0x34.toByte(), d[0])
        assertEquals(0x12.toByte(), d[1])
        assertEquals(ControlCommand.SWITCH_RECORD.value.toByte(), d[2])
        assertEquals(0x01.toByte(), d[3])
    }

    @Test
    fun `STOP 只有最后一个字节不同`() {
        val start = RecordingControlProtocol.recordingCommandData(1, enabled = true)
        val stop = RecordingControlProtocol.recordingCommandData(1, enabled = false)
        assertEquals(start.dropLast(1), stop.dropLast(1))
        assertEquals(0x00.toByte(), stop[3])
    }

    @Test
    fun `commandId 溢出要当场炸，不要静默截断成别的命令`() {
        val e = runCatching {
            RecordingControlProtocol.recordingCommandData(0x10000, enabled = true)
        }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class.java, e?.javaClass)
    }

    @Test
    fun `解析控制响应`() {
        val r = RecordingControlProtocol.parseControlResponse(byteArrayOf(0x34, 0x12, 0x00, 0x07))!!
        assertEquals(0x1234, r.commandId)
        assertEquals(0, r.status)
        assertEquals(7, r.errorCode)
    }

    @Test
    fun `响应太短返回 null，不要拿垃圾当成功`() {
        assertNull(RecordingControlProtocol.parseControlResponse(byteArrayOf(0x01, 0x02)))
    }

    @Test
    fun `没有 errorCode 字节时为 null 而不是 0`() {
        // 0 是"没有错误"的合法取值，拿缺失当 0 会把一次未知响应读成成功
        val r = RecordingControlProtocol.parseControlResponse(byteArrayOf(0x01, 0x00, 0x00))!!
        assertNull(r.errorCode)
    }
}
