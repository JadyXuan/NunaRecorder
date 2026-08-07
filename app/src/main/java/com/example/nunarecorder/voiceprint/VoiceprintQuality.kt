package com.example.nunarecorder.voiceprint

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 声纹录制的现场质量判定。
 *
 * 参与者还站在面前时重录的成本几乎为零；事后发现声纹不可用的成本是**这个人的数据
 * 永远做不了说话人分离**。所以判定要宽进严出：只拦明显不可用的，不追求音质好坏。
 *
 * 纯计算，可跑 JVM 单测。
 */
object VoiceprintQuality {

    /** 目标时长，两段各约 30 秒 */
    const val TARGET_MS = 30_000L

    /** 低于这个就太短，配不出可靠的 embedding */
    const val MIN_MS = 20_000L

    /**
     * 静音判定阈值（16-bit 满量程 32768）。
     *
     * 2026-07-31 实测设备本身电平就低（RMS 92–577，即 −50~−35 dBFS），
     * 所以这个门必须**低于**设备的正常工作电平，否则会把正常录音判成静音。
     * 取 30 ≈ −60 dBFS：明显低于设备最安静的正常值，又高于纯底噪。
     */
    const val SILENCE_RMS = 30.0

    /** 超过这个比例的样本贴到满量程就是过载 */
    const val CLIP_RATIO = 0.01

    private const val CLIP_LEVEL = 32000

    enum class Verdict { OK, TOO_SHORT, SILENT, CLIPPED }

    data class Result(
        val verdict: Verdict,
        val durationMs: Long,
        val rms: Double,
        val clipRatio: Double
    ) {
        val ok: Boolean get() = verdict == Verdict.OK

        /** 给研究员看的一句话，要能直接指导下一步动作 */
        val advice: String
            get() = when (verdict) {
                Verdict.OK -> "录制合格（%.1f 秒）".format(durationMs / 1000.0)
                Verdict.TOO_SHORT ->
                    "只有 %.1f 秒，至少需要 %d 秒，请重录".format(durationMs / 1000.0, MIN_MS / 1000)
                Verdict.SILENT ->
                    "几乎没有声音（RMS %.0f），确认设备戴好、麦克风没被挡住，然后重录".format(rms)
                Verdict.CLIPPED ->
                    "声音过载失真（%.1f%% 的采样削顶），请离设备远一点再重录".format(clipRatio * 100)
            }
    }

    /**
     * 归一化到 ±1 的单声道 PCM（`OpusToPcmMono.decodeFileToMonoFloat` 的输出）。
     *
     * 换算回 16-bit 量纲再判定，这样阈值只有一套——两套阈值迟早会漂开。
     */
    fun evaluate(pcm: FloatArray, sampleRate: Int = 16_000): Result =
        evaluate(ShortArray(pcm.size) { (pcm[it] * 32768f).toInt().coerceIn(-32768, 32767).toShort() }, sampleRate)

    /**
     * @param pcm 16-bit 单声道 PCM
     * @param sampleRate 采样率
     */
    fun evaluate(pcm: ShortArray, sampleRate: Int = 16_000): Result {
        val durationMs = if (sampleRate <= 0) 0L else pcm.size * 1000L / sampleRate
        if (pcm.isEmpty()) {
            return Result(Verdict.TOO_SHORT, 0L, 0.0, 0.0)
        }

        var sumSquares = 0.0
        var clipped = 0
        for (s in pcm) {
            val v = s.toDouble()
            sumSquares += v * v
            if (abs(s.toInt()) >= CLIP_LEVEL) clipped++
        }
        val rms = sqrt(sumSquares / pcm.size)
        val clipRatio = clipped.toDouble() / pcm.size

        // 顺序有意为之：太短最容易发生也最容易纠正，先说它；
        // 过载比静音更少见但更具体，放在静音之前。
        val verdict = when {
            durationMs < MIN_MS -> Verdict.TOO_SHORT
            clipRatio > CLIP_RATIO -> Verdict.CLIPPED
            rms < SILENCE_RMS -> Verdict.SILENT
            else -> Verdict.OK
        }
        return Result(verdict, durationMs, rms, clipRatio)
    }
}
