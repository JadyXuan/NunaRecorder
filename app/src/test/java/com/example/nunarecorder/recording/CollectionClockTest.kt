package com.example.nunarecorder.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 采集日规则必须和服务端 `collection_time.py` 逐条一致。对不上的后果是
 * 同一段音频在手机上属于这一天、在 Web 上属于另一天，而两边都觉得自己是对的。
 */
class CollectionClockTest {

    private fun hkt(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Hong_Kong")).apply {
            clear(); set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis

    /** 服务端实测过的那个例子：08-05 04:01 HKT 属于 20260805，按 UTC 会算成 20260804。 */
    @Test
    fun `切点之后属于当天`() {
        assertEquals("20260805", CollectionClock.dayId(hkt(2026, 8, 5, 4, 1)))
    }

    @Test
    fun `切点之前属于前一天`() {
        assertEquals("20260804", CollectionClock.dayId(hkt(2026, 8, 5, 3, 59)))
    }

    /** 学生常戴到过午夜；午夜之后到 04:00 之前仍算前一个佩戴日。 */
    @Test
    fun `过了午夜仍属于前一个佩戴日`() {
        assertEquals("20260805", CollectionClock.dayId(hkt(2026, 8, 6, 1, 30)))
        assertEquals("20260805", CollectionClock.dayId(hkt(2026, 8, 6, 3, 59)))
        assertEquals("20260806", CollectionClock.dayId(hkt(2026, 8, 6, 4, 0)))
    }

    @Test
    fun `一个佩戴日恰好覆盖 24 小时`() {
        assertEquals("20260805", CollectionClock.dayId(hkt(2026, 8, 5, 4, 0)))
        assertEquals("20260805", CollectionClock.dayId(hkt(2026, 8, 5, 23, 59)))
        assertEquals("20260805", CollectionClock.dayId(hkt(2026, 8, 6, 3, 59)))
        assertEquals("20260806", CollectionClock.dayId(hkt(2026, 8, 6, 4, 0)))
    }

    // ── 小时格 ────────────────────────────────────────────────────────────

    @Test
    fun `小时格对齐本地整点`() {
        val t = hkt(2026, 8, 5, 14, 37)
        assertEquals(hkt(2026, 8, 5, 14, 0), CollectionClock.hourStart(t))
        assertEquals(hkt(2026, 8, 5, 15, 0), CollectionClock.nextHourStart(t))
    }

    @Test
    fun `同一小时内不切会话，跨整点才切`() {
        val a = hkt(2026, 8, 5, 14, 0)
        assertTrue(CollectionClock.sameHour(a, hkt(2026, 8, 5, 14, 59)))
        assertFalse(CollectionClock.sameHour(a, hkt(2026, 8, 5, 15, 0)))
    }

    /** 跨采集日边界时小时格也必须切换，否则一个会话会横跨两个采集日。 */
    @Test
    fun `04 点边界同时是小时边界`() {
        val before = hkt(2026, 8, 6, 3, 59)
        val after = hkt(2026, 8, 6, 4, 0)
        assertFalse(CollectionClock.sameHour(before, after))
        assertEquals("20260805", CollectionClock.dayId(before))
        assertEquals("20260806", CollectionClock.dayId(after))
    }
}
