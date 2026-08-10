package com.example.nunarecorder.util

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-08-09 全天：有传感器的会话 IMU 各 13 万–27 万条，**GPS 全是 0**；
 * 有 GPS 的两个会话 provider 全是 network、精度 47 米。
 * 原因是注册失败被吞掉了，数据里"没有 GPS 行"和"今天没出门"分不开。
 *
 * 同意书 §3 承诺「每 30 秒记录一次 GPS」，这条不是锦上添花。
 */
class LocationRegistrationTest {

    private fun reg(
        gps: Boolean = true, net: Boolean = true,
        fine: Boolean = true, enabled: Boolean = true,
        failures: List<String> = emptyList()
    ) = LocationUpdatesHelper.Registration(gps, net, fine, enabled, failures)

    @Test
    fun `只有大致位置时要点名说是权限问题并给出改法`() {
        val p = reg(gps = false, fine = false, failures = listOf("gps:SecurityException")).problem()
        assertNotNull(p)
        assertTrue("要说清是「大致位置」", p!!.contains("大致位置"))
        assertTrue("要给出下一步", p.contains("精确位置"))
    }

    @Test
    fun `系统 GPS 关闭时不要怪权限`() {
        val p = reg(gps = false, fine = true, enabled = false).problem()!!
        assertTrue(p.contains("省电") || p.contains("高精度"))
        assertTrue("权限是好的，别误导用户去改权限", !p.contains("大致位置"))
    }

    @Test
    fun `一个都没注册上是最严重的一档`() {
        val p = reg(gps = false, net = false, fine = false, enabled = false).problem()!!
        assertTrue(p.contains("完全没能注册"))
    }

    @Test
    fun `权限和系统都正常却只剩网络定位，要如实说并让用户反馈`() {
        // 这是兜底描述，只在前两条排除之后才说——否则会把人引到错的地方去改设置
        val p = reg(gps = false, fine = true, enabled = true).problem()!!
        assertTrue(p.contains("权限和系统设置都正常"))
        assertTrue("不该再让用户去改权限", !p.contains("精确位置"))
    }

    @Test
    fun `融合定位注册上了就算通，哪怕裸 GPS provider 没注册`() {
        // fused 是户外真正拿得到卫星定位的那一路
        assertNull(reg(gps = false, net = false).copy(fusedRegistered = true).problem())
    }

    @Test
    fun `一切正常时不要制造噪音`() {
        assertNull(reg().problem())
    }

    @Test
    fun `状态行要能让人事后判断这一路是不是断的`() {
        val line = reg(gps = false, fine = false, failures = listOf("gps:SecurityException"))
            .toJsonLine(1_700_000_000_000L)
        assertTrue(line.contains("\"type\":\"gps_status\""))
        assertTrue(line.contains("\"gps_registered\":false"))
        assertTrue(line.contains("\"has_fine_permission\":false"))
        assertTrue(line.contains("gps:SecurityException"))
    }
}
