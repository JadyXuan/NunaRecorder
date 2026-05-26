package com.example.nunarecorder.vad

data class VadSegmentResult(
    val hasSpeech: Boolean,
    val speechRatio: Float,
    val speechMs: Long,
    val durationMs: Long,
    val status: String = "ok",
    val error: String? = null
)

interface SegmentVadAnalyzer {
    fun analyzeMonoPcm16k(pcm: FloatArray, durationMs: Long): VadSegmentResult
}
