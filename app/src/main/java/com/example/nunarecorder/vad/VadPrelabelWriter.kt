package com.example.nunarecorder.vad

import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionManifestIO
import com.example.nunarecorder.session.SessionPaths
import com.example.nunarecorder.util.writeTextAtomic
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class VadPrelabelSegment(
    val index: Int,
    val audioFile: String,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val hasSpeech: Boolean,
    val speechRatio: Float,
    val speechMs: Long,
    val analyzedAtMs: Long,
    val status: String,
    val error: String? = null
)

/**
 * 写入 `labels/vad_prelabel.json`（Silero VAD 预标注）。
 *
 * Schema: [docs/SESSION_SYNC_PROTOCOL.md] (section 1, `labels/vad_prelabel.json`).
 */
object VadPrelabelWriter {

    private const val SPEECH_THRESHOLD_RATIO = 0.15

    fun prelabelFile(sessionDir: File): File = SessionPaths.vadPrelabelFile(sessionDir)

    fun loadSegments(file: File): MutableList<VadPrelabelSegment> {
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONObject(file.readText()).getJSONArray("segments")
            val list = mutableListOf<VadPrelabelSegment>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    VadPrelabelSegment(
                        index = o.getInt("index"),
                        audioFile = o.getString("audio_file"),
                        startMs = o.getLong("start_ms"),
                        endMs = o.getLong("end_ms"),
                        durationMs = o.getLong("duration_ms"),
                        hasSpeech = o.getBoolean("has_speech"),
                        speechRatio = o.getDouble("speech_ratio").toFloat(),
                        speechMs = o.getLong("speech_ms"),
                        analyzedAtMs = o.getLong("analyzed_at_ms"),
                        status = o.getString("status"),
                        error = o.optString("error").takeIf { it.isNotEmpty() }
                    )
                )
            }
            list
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun mergeSegment(
        sessionDir: File,
        segment: VadPrelabelSegment,
        engine: String = "silero",
        engineVersion: String = SileroVadAnalyzer.MODEL_VERSION,
        sessionStartedAtMs: Long
    ) {
        val file = prelabelFile(sessionDir)
        file.parentFile?.mkdirs()
        val root = try {
            if (file.exists()) JSONObject(file.readText()) else null
        } catch (_: Exception) {
            null
        } ?: JSONObject().apply {
            put("format_version", 1)
            put("vad_engine", engine)
            put("vad_engine_version", engineVersion)
            put("params", JSONObject().apply {
                put("sample_rate_hz", 16000)
                put("window_samples", 512)
                put("speech_threshold", 0.5)
                put("speech_threshold_ratio", SPEECH_THRESHOLD_RATIO)
            })
            put("session_started_at_ms", sessionStartedAtMs)
            put("segments", JSONArray())
        }
        val segments = root.getJSONArray("segments")
        var replaced = false
        for (i in 0 until segments.length()) {
            if (segments.getJSONObject(i).getInt("index") == segment.index) {
                segments.put(i, segment.toJson())
                replaced = true
                break
            }
        }
        if (!replaced) segments.put(segment.toJson())
        val expectedTotal = SessionManifest.load(SessionPaths.manifestFile(sessionDir))?.segments?.size
        updateSummary(root, expectedTotal)
        file.writeTextAtomic(root.toString(2))
        refreshManifestSummary(sessionDir, root, expectedTotal)
    }

    private fun updateSummary(root: JSONObject, expectedTotal: Int?) {
        val segments = root.getJSONArray("segments")
        var speech = 0
        var ok = 0
        for (i in 0 until segments.length()) {
            val o = segments.getJSONObject(i)
            if (o.getString("status") == "ok") ok++
            if (o.optBoolean("has_speech")) speech++
        }
        val total = expectedTotal ?: segments.length()
        root.put(
            "summary",
            JSONObject().apply {
                put("total_segments", total)
                put("speech_segments", speech)
                put("analyzed_segments", ok)
            }
        )
    }

    private fun refreshManifestSummary(
        sessionDir: File,
        root: JSONObject,
        expectedTotal: Int?
    ) {
        val summary = root.getJSONObject("summary")
        val analyzed = summary.getInt("analyzed_segments")
        val total = expectedTotal ?: summary.getInt("total_segments")
        val speech = summary.getInt("speech_segments")
        val status = when {
            total <= 0 -> "pending"
            analyzed >= total -> "complete"
            else -> "partial"
        }
        SessionManifestIO.updateVadSummary(sessionDir, status, speech, total)
    }

    private fun VadPrelabelSegment.toJson(): JSONObject = JSONObject().apply {
        put("index", index)
        put("audio_file", audioFile)
        put("start_ms", startMs)
        put("end_ms", endMs)
        put("duration_ms", durationMs)
        put("has_speech", hasSpeech)
        put("speech_ratio", speechRatio.toDouble())
        put("speech_ms", speechMs)
        put("analyzed_at_ms", analyzedAtMs)
        put("status", status)
        if (error != null) put("error", error)
    }

    fun markRunning(sessionDir: File, totalSegments: Int, sessionStartedAtMs: Long) {
        SessionManifestIO.updateVadSummary(sessionDir, "running", 0, totalSegments)
        val f = prelabelFile(sessionDir)
        if (!f.exists()) {
            f.parentFile?.mkdirs()
            val root = JSONObject().apply {
                put("format_version", 1)
                put("vad_engine", "silero")
                put("vad_engine_version", SileroVadAnalyzer.MODEL_VERSION)
                put("session_started_at_ms", sessionStartedAtMs)
                put("segments", JSONArray())
                put("summary", JSONObject().apply {
                    put("total_segments", totalSegments)
                    put("speech_segments", 0)
                    put("analyzed_segments", 0)
                })
            }
            f.writeTextAtomic(root.toString(2))
        }
    }
}
