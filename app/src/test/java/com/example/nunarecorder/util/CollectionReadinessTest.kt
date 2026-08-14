package com.example.nunarecorder.util

import com.example.nunarecorder.util.CollectionReadiness.Fix
import com.example.nunarecorder.util.CollectionReadiness.Item
import com.example.nunarecorder.util.CollectionReadiness.Level
import com.example.nunarecorder.util.CollectionReadiness.Report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `check()` 要 Android Context，跑不了 JVM 单测；能在这里锁住的是**判据**。
 *
 * 用户 2026-08-09：「没看到红/黄啊（没有黄，红确实正常）」。
 * 根因不在这里——`readinessReport` 当时根本没有任何 Composable 读它，
 * 只写进日志。但判据本身也必须保证：**只有降级项时卡片仍然要显示**。
 * 如果 `allGood` 把 DEGRADED 当成好，黄色那一档就永远不会亮，
 * 而一个从不告警的自检等于没有自检。
 */
class CollectionReadinessTest {

    private fun report(vararg levels: Level) = Report(
        levels.mapIndexed { i, l -> Item(l, "item$i", "consequence$i") }
    )

    @Test
    fun `只有降级项时仍然要显示，但不挡住采集`() {
        val r = report(Level.OK, Level.DEGRADED)
        assertTrue("降级不该阻断采集", r.canRecord)
        assertFalse("有降级项就不能算全好，否则卡片不显示", r.allGood)
        assertEquals(1, r.degraded.size)
    }

    @Test
    fun `全部正常时不显示`() {
        val r = report(Level.OK, Level.OK)
        assertTrue(r.allGood)
        assertTrue(r.canRecord)
        assertEquals("环境自检全部通过", r.summary())
    }

    @Test
    fun `阻断项优先出现在摘要里`() {
        val r = Report(
            listOf(
                Item(Level.DEGRADED, "缺少位置权限", "没有 GPS"),
                Item(Level.BLOCKING, "蓝牙未开启", "采不了")
            )
        )
        assertFalse(r.canRecord)
        assertTrue(r.summary().contains("蓝牙未开启"))
        assertFalse("阻断时不该把降级项混进同一句", r.summary().contains("缺少位置权限"))
    }

    @Test
    fun `省电模式是降级项且能跳到设置`() {
        // 事前告警。T-025 的自我拉起是兜底，不是替代品——被杀掉的那段时间
        // 采集是真的没了，拉起来也补不回来。
        val item = Item(
            Level.DEGRADED, "系统省电模式正开着", "会被杀掉",
            "去关掉", Fix.BATTERY_SAVER
        )
        assertEquals(Level.DEGRADED, item.level)
        assertEquals(Fix.BATTERY_SAVER, item.fix)
    }

    @Test
    fun `没有跳转目标的项默认是 NONE`() {
        // Google Play 服务缺失是机型限制，跳到任何设置页都没用，
        // 给个点了没反应的按钮比不给更糟。
        assertEquals(Fix.NONE, Item(Level.DEGRADED, "t", "c").fix)
    }
}
