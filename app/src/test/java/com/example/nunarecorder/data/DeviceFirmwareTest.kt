package com.example.nunarecorder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * T-2026-08-14-044：旧固件的设备会**静默地完全没有毫米波**。
 *
 * 实测 `4C:FF:01:A0:05:7A` 跑 `3.14.5.1736`，连续 51 分钟零个包，
 * 而 manifest 上除了 `device_firmware` 没有任何地方看得出来。
 *
 * 这里锁住两件容易做错的事：
 * 1. **不能把 DIS 的 `1.0.0` 当成真固件。** 同一台设备（`01:8A`）记录过
 *    `1.0.0` 28 次和 `3.14.5.1813` 4 次——存 `1.0.0` 等于把"未知"记成"已知且正常"，
 *    检查就永远放行，比不检查更糟。
 * 2. **更新"最近连接时间"不能顺手抹掉固件。** 固件只有采集跑起来之后才拿得到。
 */
class DeviceFirmwareTest {

    private val expected = DeviceFirmwarePolicy.EXPECTED

    @Test
    fun `DIS 的通用串不算知道固件`() {
        assertEquals(
            DeviceFirmwarePolicy.Verdict.UNKNOWN,
            DeviceFirmwarePolicy.verdict(DeviceFirmwarePolicy.DIS_PLACEHOLDER)
        )
        assertNull("未知不该告警——第一次用新设备本来就不知道",
            DeviceFirmwarePolicy.warning(DeviceFirmwarePolicy.DIS_PLACEHOLDER))
    }

    @Test
    fun `没采过的设备是未知，不是不合格`() {
        assertEquals(DeviceFirmwarePolicy.Verdict.UNKNOWN, DeviceFirmwarePolicy.verdict(null))
        assertEquals(DeviceFirmwarePolicy.Verdict.UNKNOWN, DeviceFirmwarePolicy.verdict(""))
        assertNull(DeviceFirmwarePolicy.warning(null))
    }

    @Test
    fun `基线固件不告警，旧固件告警且说清后果`() {
        assertEquals(DeviceFirmwarePolicy.Verdict.MATCH, DeviceFirmwarePolicy.verdict(expected))
        assertNull(DeviceFirmwarePolicy.warning(expected))

        assertEquals(
            DeviceFirmwarePolicy.Verdict.MISMATCH,
            DeviceFirmwarePolicy.verdict("3.14.5.1736")
        )
        val w = DeviceFirmwarePolicy.warning("3.14.5.1736")
        assertNotNull(w)
        assertEquals(true, w!!.contains("3.14.5.1736"))
        assertEquals("要说清后果，不能只说版本号对不上", true, w.contains("毫米波"))
        assertEquals("音频不受影响这句必须在，否则参与者会以为不能采了", true, w.contains("音频"))
    }

    @Test
    fun `只更新连接时间不能抹掉已知固件`() {
        // 开始采集时 MainActivity 会现构一个不带固件的 PairedDevice 来刷新时间戳。
        // 整条替换会把固件抹掉，而它要重采一次才补得回来，界面上还看不出来。
        val stored = listOf(PairedDevice("nuna", "AA:BB", 1L, lastFirmware = expected))
        val after = DeviceStorage.upsert(stored, PairedDevice("nuna", "AA:BB", 999L))

        assertEquals(1, after.size)
        assertEquals(999L, after[0].lastConnectedTime)
        assertEquals(expected, after[0].lastFirmware)
    }

    @Test
    fun `新设备直接加进去`() {
        val after = DeviceStorage.upsert(emptyList(), PairedDevice("n", "AA:BB", 1L))
        assertEquals(1, after.size)
        assertNull(after[0].lastFirmware)
    }

    @Test
    fun `明确带固件时可以覆盖旧值`() {
        // 设备真升级过固件，新值必须能写进去
        val stored = listOf(PairedDevice("n", "AA:BB", 1L, lastFirmware = "3.14.5.1736"))
        val after = DeviceStorage.upsert(stored, PairedDevice("n", "AA:BB", 2L, expected))
        assertEquals(expected, after[0].lastFirmware)
    }

    @Test
    fun `withFirmware 拒绝 DIS 的值，也不写没配对过的设备`() {
        val stored = listOf(PairedDevice("n", "AA:BB", 1L))
        assertSame(
            "DIS 的 1.0.0 不能落盘",
            stored, DeviceStorage.withFirmware(stored, "AA:BB", DeviceFirmwarePolicy.DIS_PLACEHOLDER)
        )
        assertSame(
            "没配对过的地址不该凭空创建记录",
            stored, DeviceStorage.withFirmware(stored, "CC:DD", expected)
        )
    }

    @Test
    fun `withFirmware 值没变时返回原列表，避免每次通知都写盘`() {
        val stored = listOf(PairedDevice("n", "AA:BB", 1L, lastFirmware = expected))
        assertSame(stored, DeviceStorage.withFirmware(stored, "AA:BB", expected))

        val changed = DeviceStorage.withFirmware(stored, "AA:BB", "3.14.5.1736")
        assertEquals("3.14.5.1736", changed[0].lastFirmware)
    }
}
