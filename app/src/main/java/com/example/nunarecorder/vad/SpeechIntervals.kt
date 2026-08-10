package com.example.nunarecorder.vad

/**
 * 把每窗的语音判定合并成**人能用的时间区间**。
 *
 * 端侧 VAD 的用途 2026-08-10 变了：原来是给服务端 ASR 做成本路由，
 * 而实测证明那条理由在本地 GPU 上不成立（静音段本来就快，跳过它们只省约 10% 时间）。
 * 现在它服务的是**参与者在上传前找到要删的东西**——用户原话：
 *
 * > 刚说完，意识到有话不能说，立即去找 vad 有说话的部分，还好删
 *
 * 这是个时间窗很短的召回任务。一个 60 秒的段里只有 17 秒有语音时，
 * 直接告诉他「第 12–29 秒有说话」，他就不用整段听。
 *
 * 所以合并规则是按**人的可读性**定的，不是按 VAD 的精度：
 * - 32 ms 一格的原始判定直接给人看是没用的，会变成一串碎片；
 * - 说话中间的换气和停顿（几百毫秒）不该把一句话切成三段；
 * - 单个窗口的误触发不该显示成一个"区间"。
 *
 * 这条路径**不参与**服务端 ASR 路由，那条仍然看 `has_speech`。
 */
object SpeechIntervals {

    /** 间隔小于这个值的两段语音合成一段：正常换气和停顿 */
    const val MERGE_GAP_MS = 400L

    /** 短于这个值的语音段丢掉：多半是误触发，显示出来只会干扰 */
    const val MIN_SPEECH_MS = 250L

    data class Interval(val startMs: Long, val endMs: Long) {
        val durationMs: Long get() = endMs - startMs
    }

    /**
     * @param speechFlags 每个窗口是否判定为语音，按时间顺序
     * @param windowMs 一个窗口多少毫秒（512 samples @ 16 kHz = 32 ms）
     */
    fun fromWindows(
        speechFlags: List<Boolean>,
        windowMs: Long,
        mergeGapMs: Long = MERGE_GAP_MS,
        minSpeechMs: Long = MIN_SPEECH_MS
    ): List<Interval> {
        if (speechFlags.isEmpty() || windowMs <= 0L) return emptyList()

        val raw = mutableListOf<Interval>()
        var runStart = -1
        speechFlags.forEachIndexed { i, isSpeech ->
            if (isSpeech && runStart < 0) runStart = i
            if (!isSpeech && runStart >= 0) {
                raw.add(Interval(runStart * windowMs, i * windowMs))
                runStart = -1
            }
        }
        if (runStart >= 0) raw.add(Interval(runStart * windowMs, speechFlags.size * windowMs))

        // 先合并再过滤：两段各 200 ms、中间隔 100 ms 的，合起来是一句完整的话，
        // 先过滤会把它整个丢掉。
        val merged = mutableListOf<Interval>()
        for (iv in raw) {
            val last = merged.lastOrNull()
            if (last != null && iv.startMs - last.endMs <= mergeGapMs) {
                merged[merged.size - 1] = last.copy(endMs = iv.endMs)
            } else {
                merged.add(iv)
            }
        }
        return merged.filter { it.durationMs >= minSpeechMs }
    }

    /** 给界面看的一行，例如「0:12–0:29、0:41–0:58」 */
    fun describe(intervals: List<Interval>, max: Int = 4): String {
        if (intervals.isEmpty()) return "没有检测到说话"
        val shown = intervals.take(max).joinToString("、") {
            "${mmss(it.startMs)}–${mmss(it.endMs)}"
        }
        return if (intervals.size > max) "$shown 等 ${intervals.size} 处" else shown
    }

    private fun mmss(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }
}
