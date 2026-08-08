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

    /**
     * 两段的最低时长，**上不封顶**。
     *
     * 朗读段全体读同一段文字，读完就够，30 秒是够的。
     * 自由说话段不一样：用户 2026-08-07 指出「一天怎么过」本身可能包含重要信息，
     * 值得让参与者好好讲。声纹只需要够长，但**多讲没有坏处**——
     * 更长的自然语音反而让说话人 embedding 更稳，而讲出来的内容对研究也有价值。
     * 所以这里只设下限，界面上不设倒计时终点，参与者想讲多久讲多久。
     *
     * 2026-08-09 协调者定下限 120 秒（用户睡了，他按职权给默认值，用户明早可推翻）：
     * 90 秒讲一天太赶，容易讲成流水账；120 秒能讲出结构，又不至于让人在入组现场
     * 站着尴尬。
     */
    const val MIN_READ_MS = 25_000L
    const val MIN_FREE_MS = 120_000L

    /** 建议时长，仅用于界面上的进度提示，不是上限 */
    const val SUGGESTED_READ_MS = 30_000L
    const val SUGGESTED_FREE_MS = 120_000L

    @Deprecated("按段区分下限", ReplaceWith("MIN_READ_MS"))
    const val MIN_MS = 25_000L

    @Deprecated("不再有统一目标时长", ReplaceWith("SUGGESTED_READ_MS"))
    const val TARGET_MS = 30_000L

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
        val clipRatio: Double,
        val minDurationMs: Long = MIN_READ_MS
    ) {
        val ok: Boolean get() = verdict == Verdict.OK

        /** 给研究员看的一句话，要能直接指导下一步动作 */
        val advice: String
            get() = when (verdict) {
                Verdict.OK -> "录制合格（%.1f 秒）".format(durationMs / 1000.0)
                Verdict.TOO_SHORT ->
                    "只有 %.0f 秒，至少要 %d 秒，请重录".format(
                        durationMs / 1000.0, minDurationMs / 1000
                    )
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
    fun evaluate(
        pcm: FloatArray,
        sampleRate: Int = 16_000,
        minDurationMs: Long = MIN_READ_MS
    ): Result =
        evaluate(
            ShortArray(pcm.size) { (pcm[it] * 32768f).toInt().coerceIn(-32768, 32767).toShort() },
            sampleRate,
            minDurationMs
        )

    /**
     * @param pcm 16-bit 单声道 PCM
     * @param sampleRate 采样率
     */
    fun evaluate(
        pcm: ShortArray,
        sampleRate: Int = 16_000,
        minDurationMs: Long = MIN_READ_MS
    ): Result {
        val durationMs = if (sampleRate <= 0) 0L else pcm.size * 1000L / sampleRate
        if (pcm.isEmpty()) {
            return Result(Verdict.TOO_SHORT, 0L, 0.0, 0.0, minDurationMs)
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
            durationMs < minDurationMs -> Verdict.TOO_SHORT
            clipRatio > CLIP_RATIO -> Verdict.CLIPPED
            rms < SILENCE_RMS -> Verdict.SILENT
            else -> Verdict.OK
        }
        return Result(verdict, durationMs, rms, clipRatio, minDurationMs)
    }
}
