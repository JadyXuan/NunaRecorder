package com.example.nunarecorder.vad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端侧 VAD 2026-08-10 起服务的是"参与者在上传前找到要删的东西"。
 * 用户原话：「刚说完，意识到有话不能说，立即去找 vad 有说话的部分，还好删」。
 *
 * 所以合并规则按**人的可读性**定，不按 VAD 精度：一串 32 ms 的碎片对人没用。
 */
class SpeechIntervalsTest {

    /** 32 ms 一窗，和 Silero 的 512 samples @16 kHz 一致 */
    private val w = 32L

    private fun flags(pattern: String) = pattern.map { it == '1' }

    @Test
    fun `连续语音合成一段`() {
        val iv = SpeechIntervals.fromWindows(flags("0011111111110"), w)
        assertEquals(1, iv.size)
        assertEquals(2 * w, iv[0].startMs)
        assertEquals(12 * w, iv[0].endMs)
    }

    @Test
    fun `换气造成的短停顿不该把一句话切成两段`() {
        val iv = SpeechIntervals.fromWindows(flags("1111111111" + "000" + "1111111111"), w)
        assertEquals("间隔小于 400ms 该合并", 1, iv.size)
        assertEquals(23 * w, iv[0].endMs)
    }

    @Test
    fun `真正的长停顿要分开，否则删除区间会圈进不该删的部分`() {
        val iv = SpeechIntervals.fromWindows(flags("1111111111" + "0".repeat(20) + "1111111111"), w)
        assertEquals(2, iv.size)
    }

    @Test
    fun `单窗误触发不显示成区间`() {
        val iv = SpeechIntervals.fromWindows(flags("000010000"), w)
        assertTrue("32ms 的误触发不该出现在界面上", iv.isEmpty())
    }

    @Test
    fun `先合并后过滤：两段短的中间隔得近，合起来是一句完整的话`() {
        val iv = SpeechIntervals.fromWindows(flags("1111" + "00" + "1111"), w)
        assertEquals("先过滤会把这句话整个丢掉", 1, iv.size)
        assertEquals(10 * w, iv[0].endMs)
    }

    @Test
    fun `末尾还在说话时要收口，不能丢掉最后一段`() {
        val iv = SpeechIntervals.fromWindows(flags("00" + "1".repeat(20)), w)
        assertEquals(1, iv.size)
        assertEquals(22 * w, iv[0].endMs)
    }

    @Test
    fun `空输入不炸`() {
        assertTrue(SpeechIntervals.fromWindows(emptyList(), w).isEmpty())
    }

    @Test
    fun `描述要让人一眼知道去听哪里`() {
        val iv = listOf(
            SpeechIntervals.Interval(12_000, 29_000),
            SpeechIntervals.Interval(41_000, 58_000)
        )
        assertEquals("0:12–0:29、0:41–0:58", SpeechIntervals.describe(iv))
        assertEquals("没有检测到说话", SpeechIntervals.describe(emptyList()))
    }

    @Test
    fun `区间太多时不要刷屏，但要说清一共几处`() {
        val many = (1..9).map { SpeechIntervals.Interval(it * 1000L, it * 1000L + 500) }
        assertTrue(SpeechIntervals.describe(many).endsWith("等 9 处"))
    }
}
