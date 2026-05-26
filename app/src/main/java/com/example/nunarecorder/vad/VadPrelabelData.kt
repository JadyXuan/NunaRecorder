package com.example.nunarecorder.vad

import com.example.nunarecorder.session.SessionPaths
import org.json.JSONObject
import java.io.File

data class VadPrelabelSummary(
    val totalSegments: Int,
    val speechSegments: Int,
    val analyzedSegments: Int
)

data class VadPrelabelData(
    val engine: String,
    val engineVersion: String,
    val sessionStartedAtMs: Long,
    val summary: VadPrelabelSummary,
    val segments: List<VadPrelabelSegment>
) {
    val isComplete: Boolean get() =
        summary.totalSegments > 0 && summary.analyzedSegments >= summary.totalSegments
}

object VadPrelabelReader {

    fun load(sessionDir: File): VadPrelabelData? {
        val file = SessionPaths.vadPrelabelFile(sessionDir)
        if (!file.exists()) return null
        return try {
            val root = JSONObject(file.readText())
            val summaryJ = root.optJSONObject("summary")
            VadPrelabelData(
                engine = root.optString("vad_engine", "silero"),
                engineVersion = root.optString("vad_engine_version", ""),
                sessionStartedAtMs = root.optLong("session_started_at_ms", 0L),
                summary = VadPrelabelSummary(
                    totalSegments = summaryJ?.optInt("total_segments") ?: 0,
                    speechSegments = summaryJ?.optInt("speech_segments") ?: 0,
                    analyzedSegments = summaryJ?.optInt("analyzed_segments") ?: 0
                ),
                segments = VadPrelabelWriter.loadSegments(file).sortedBy { it.index }
            )
        } catch (_: Exception) {
            null
        }
    }
}
