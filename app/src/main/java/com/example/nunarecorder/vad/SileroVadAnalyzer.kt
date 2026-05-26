package com.example.nunarecorder.vad

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.min

/**
 * Silero VAD v4 ONNX（assets/silero_vad.onnx），16 kHz。
 * 模型输入为 input + state + sr（非旧版 h/c/sr 四输入）。
 * 参考：https://github.com/snakers4/silero-vad/blob/master/src/silero_vad/utils_vad.py OnnxWrapper
 */
class SileroVadAnalyzer private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession
) : SegmentVadAnalyzer {

    companion object {
        private const val TAG = "SileroVadAnalyzer"
        private const val MODEL_ASSET = "silero_vad.onnx"
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_SAMPLES = 512
        private const val CONTEXT_SAMPLES = 64
        private const val STATE_DIM = 128
        private const val SPEECH_THRESHOLD = 0.5f
        private const val HAS_SPEECH_RATIO = 0.15f
        const val MODEL_VERSION = "silero_v4_onnx"

        @Volatile
        private var instance: SileroVadAnalyzer? = null

        fun getInstance(context: Context): SileroVadAnalyzer {
            return instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }
        }

        private fun create(context: Context): SileroVadAnalyzer {
            val tmp = File(context.cacheDir, MODEL_ASSET)
            if (!tmp.exists()) {
                context.assets.open(MODEL_ASSET).use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
            }
            val session = env.createSession(tmp.absolutePath, opts)
            Log.d(TAG, "ONNX inputs=${session.inputNames.joinToString()} outputs=${session.outputNames.joinToString()}")
            return SileroVadAnalyzer(env, session)
        }
    }

    /** v4: shape (2, 1, 128) */
    private var state: Array<Array<FloatArray>> = zeroState()
    private var context: FloatArray = FloatArray(CONTEXT_SAMPLES)

    private fun zeroState(): Array<Array<FloatArray>> =
        Array(2) { Array(1) { FloatArray(STATE_DIM) } }

    private fun resetStates() {
        state = zeroState()
        context = FloatArray(CONTEXT_SAMPLES)
    }

    override fun analyzeMonoPcm16k(pcm: FloatArray, durationMs: Long): VadSegmentResult {
        if (pcm.isEmpty()) {
            return VadSegmentResult(
                hasSpeech = false,
                speechRatio = 0f,
                speechMs = 0,
                durationMs = durationMs,
                status = "skipped",
                error = "empty_pcm"
            )
        }
        return try {
            resetStates()
            var speechWindows = 0
            var totalWindows = 0
            var offset = 0
            while (offset < pcm.size) {
                val remaining = pcm.size - offset
                val window = FloatArray(WINDOW_SAMPLES)
                if (remaining >= WINDOW_SAMPLES) {
                    System.arraycopy(pcm, offset, window, 0, WINDOW_SAMPLES)
                    offset += WINDOW_SAMPLES
                } else {
                    System.arraycopy(pcm, offset, window, 0, remaining)
                    offset = pcm.size
                }
                val prob = runWindowV4(window)
                totalWindows++
                if (prob >= SPEECH_THRESHOLD) speechWindows++
            }
            val ratio = if (totalWindows > 0) speechWindows.toFloat() / totalWindows else 0f
            val speechMs = (ratio * durationMs).toLong()
            VadSegmentResult(
                hasSpeech = ratio >= HAS_SPEECH_RATIO,
                speechRatio = ratio,
                speechMs = speechMs,
                durationMs = durationMs,
                status = "ok"
            )
        } catch (e: Exception) {
            Log.e(TAG, "VAD analyze failed", e)
            VadSegmentResult(
                hasSpeech = false,
                speechRatio = 0f,
                speechMs = 0,
                durationMs = durationMs,
                status = "failed",
                error = e.message
            )
        }
    }

    private fun runWindowV4(window: FloatArray): Float {
        val inputLen = CONTEXT_SAMPLES + WINDOW_SAMPLES
        val inputData = FloatArray(inputLen)
        System.arraycopy(context, 0, inputData, 0, CONTEXT_SAMPLES)
        System.arraycopy(window, 0, inputData, CONTEXT_SAMPLES, WINDOW_SAMPLES)

        val input = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(inputData),
            longArrayOf(1, inputLen.toLong())
        )
        val sr = OnnxTensor.createTensor(env, longArrayOf(SAMPLE_RATE.toLong()))
        val stateT = OnnxTensor.createTensor(env, state)
        val results = session.run(
            mapOf(
                "input" to input,
                "state" to stateT,
                "sr" to sr
            )
        )
        input.close()
        sr.close()
        stateT.close()

        val prob = extractProb(results[0].value)
        @Suppress("UNCHECKED_CAST")
        state = results[1].value as Array<Array<FloatArray>>
        System.arraycopy(inputData, inputLen - CONTEXT_SAMPLES, context, 0, CONTEXT_SAMPLES)
        results.close()
        return prob
    }

    private fun extractProb(value: Any?): Float {
        return when (value) {
            is Array<*> -> {
                val first = value[0]
                when (first) {
                    is FloatArray -> first[0]
                    is Array<*> -> extractProb(first)
                    is Float -> first
                    else -> 0f
                }
            }
            is FloatArray -> value[0]
            is Float -> value
            else -> 0f
        }
    }
}
