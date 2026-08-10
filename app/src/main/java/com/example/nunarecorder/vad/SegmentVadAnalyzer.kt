package com.example.nunarecorder.vad

data class VadSegmentResult(
    val hasSpeech: Boolean,
    val speechRatio: Float,
    val speechMs: Long,
    val durationMs: Long,
    val status: String = "ok",
    val error: String? = null,
    /**
     * 语音区间（毫秒，相对段起点）。**新增字段，不改既有的。**
     *
     * 服务端 ASR 路由看的仍然是 `has_speech`（`vad_source: segment`），
     * 这一路只服务于"参与者在上传前找到要删的东西"。见 [SpeechIntervals]。
     */
    val speechIntervals: List<SpeechIntervals.Interval> = emptyList()
)

interface SegmentVadAnalyzer {
    fun analyzeMonoPcm16k(pcm: FloatArray, durationMs: Long): VadSegmentResult
}
