package com.example.nunarecorder.voiceprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

/**
 * 现场重录的成本几乎为零，事后发现声纹不可用的成本是这个人的数据永远做不了
 * 说话人分离。所以这些判定必须在参与者还在场时就给出结论。
 */
class VoiceprintQualityTest {

    private val sr = 16_000

    private fun tone(seconds: Double, amplitude: Int): ShortArray {
        val n = (sr * seconds).toInt()
        return ShortArray(n) { (amplitude * sin(2 * Math.PI * 220 * it / sr)).toInt().toShort() }
    }

    private fun noise(seconds: Double, amplitude: Int, seed: Int = 1): ShortArray {
        val rnd = Random(seed)
        val n = (sr * seconds).toInt()
        return ShortArray(n) { rnd.nextInt(-amplitude, amplitude + 1).toShort() }
    }

    @Test
    fun `正常 30 秒语音合格`() {
        val r = VoiceprintQuality.evaluate(noise(30.0, 2000), sr)
        assertEquals(r.advice, VoiceprintQuality.Verdict.OK, r.verdict)
        assertEquals(30_000L, r.durationMs)
    }

    @Test
    fun `录 3 秒当场判太短`() {
        val r = VoiceprintQuality.evaluate(noise(3.0, 2000), sr)
        assertEquals(VoiceprintQuality.Verdict.TOO_SHORT, r.verdict)
        assertTrue(r.advice, r.advice.contains("重录"))
    }

    @Test
    fun `一段静音当场判静音`() {
        val r = VoiceprintQuality.evaluate(ShortArray(sr * 30), sr)
        assertEquals(VoiceprintQuality.Verdict.SILENT, r.verdict)
        assertTrue(r.advice, r.advice.contains("麦克风"))
    }

    @Test
    fun `过载被判出来`() {
        val r = VoiceprintQuality.evaluate(tone(30.0, 32767), sr)
        assertEquals(VoiceprintQuality.Verdict.CLIPPED, r.verdict)
        assertTrue(r.advice, r.advice.contains("远一点"))
    }

    /**
     * 关键：设备本身电平就低（2026-07-31 实测 RMS 92–577，即 −50~−35 dBFS）。
     * 静音门必须低于设备的正常工作电平，否则会把**合格的录音判成静音**，
     * 让参与者反复重录一段本来就没问题的声纹。
     */
    @Test
    fun `设备的低电平正常录音不会被误判成静音`() {
        for (rmsTarget in listOf(92, 200, 577)) {
            val r = VoiceprintQuality.evaluate(noise(30.0, rmsTarget * 2), sr)
            assertEquals(
                "RMS 约 $rmsTarget 是设备实测的正常范围，不该判静音（$r）",
                VoiceprintQuality.Verdict.OK, r.verdict
            )
        }
    }

    @Test
    fun `刚好卡在最短时长边界`() {
        assertEquals(
            VoiceprintQuality.Verdict.OK,
            VoiceprintQuality.evaluate(noise(20.0, 2000), sr).verdict
        )
        assertEquals(
            VoiceprintQuality.Verdict.TOO_SHORT,
            VoiceprintQuality.evaluate(noise(19.9, 2000), sr).verdict
        )
    }

    @Test
    fun `空输入不崩`() {
        val r = VoiceprintQuality.evaluate(ShortArray(0), sr)
        assertEquals(VoiceprintQuality.Verdict.TOO_SHORT, r.verdict)
        assertEquals(0L, r.durationMs)
    }
}
