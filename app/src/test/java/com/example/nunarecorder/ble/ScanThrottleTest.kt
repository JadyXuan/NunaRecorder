package com.example.nunarecorder.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 30 秒内最多允许一个应用启动 5 次扫描，超了就**静默**挡掉：
 * `startScan()` 照常返回，`onScanFailed` 多数机型不回调，界面只会一直"扫描中"。
 *
 * 用户 2026-08-09：「有的时候还会卡住，附近的 nuna 设备一台都没有，重启后才看得到。」
 */
class ScanThrottleTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `窗口内前四次放行，第五次拦住并给出等待时间`() {
        val th = ScanThrottle()
        repeat(4) { i ->
            assertTrue("第 ${i + 1} 次该放行", th.tryStart(t0 + i * 1000L) is ScanThrottle.Decision.Allowed)
        }
        val d = th.tryStart(t0 + 4000L)
        assertTrue(d is ScanThrottle.Decision.Throttled)
        // 最早那次在 t0，30 秒后才腾出名额
        assertEquals(26_000L, (d as ScanThrottle.Decision.Throttled).waitMs)
    }

    @Test
    fun `滑出窗口之后重新放行`() {
        val th = ScanThrottle()
        repeat(4) { th.tryStart(t0) }
        assertTrue(th.tryStart(t0 + 29_999L) is ScanThrottle.Decision.Throttled)
        assertTrue(th.tryStart(t0 + 30_000L) is ScanThrottle.Decision.Allowed)
    }

    @Test
    fun `被拦下的那次不计数，否则永远解不开`() {
        val th = ScanThrottle()
        repeat(4) { th.tryStart(t0) }
        // 用户在窗口内反复点，每次都被拦——这些都不该延长封锁
        repeat(10) { th.tryStart(t0 + 1000L + it) }
        assertTrue(th.tryStart(t0 + 30_000L) is ScanThrottle.Decision.Allowed)
    }

    @Test
    fun `重置之后立刻可用`() {
        val th = ScanThrottle()
        repeat(4) { th.tryStart(t0) }
        th.reset()
        assertTrue(th.tryStart(t0) is ScanThrottle.Decision.Allowed)
    }

    @Test
    fun `错误码要说人话且给得出下一步`() {
        val two = ScanThrottle.describeScanFailure(2)
        assertTrue("code=2 最常见的成因是扫描没关掉", two.contains("蓝牙"))
        val six = ScanThrottle.describeScanFailure(6)
        assertTrue(six.contains("限流"))
        assertTrue("要告诉用户等多久", six.contains("30"))
        assertTrue(ScanThrottle.describeScanFailure(99).contains("99"))
    }
}
