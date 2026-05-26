package com.example.nunarecorder.session

import com.example.nunarecorder.util.writeTextAtomic
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class AudioSegmentEntry(
    val index: Int,
    val file: String,
    val startMs: Long,
    val endMs: Long,
    val bytes: Long,
    val durationMs: Long
)

data class VadSummary(
    val prelabelFile: String = SessionPaths.VAD_PRELABEL_FILE,
    val status: String = "pending",
    val speechSegments: Int = 0,
    val totalSegments: Int = 0
)

/**
 * 会话 `manifest.json`（format_version = 1）。
 *
 * Schema: [docs/SESSION_SYNC_PROTOCOL.md] (section 1.1).
 */
data class SessionManifest(
    val formatVersion: Int = SessionPaths.FORMAT_VERSION,
    val sessionId: String,
    val deviceName: String,
    val deviceAddress: String?,
    val startedAtMs: Long,
    var endedAtMs: Long? = null,
    val segmentDurationMs: Long = SessionPaths.SEGMENT_DURATION_MS,
    val segments: MutableList<AudioSegmentEntry> = mutableListOf(),
    var vad: VadSummary = VadSummary(),
    val legacy: Boolean = false,
    val sourceOpus: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("format_version", formatVersion)
        put("session_id", sessionId)
        put("device_name", deviceName)
        put("device_address", deviceAddress ?: JSONObject.NULL)
        put("started_at_ms", startedAtMs)
        put("ended_at_ms", endedAtMs ?: JSONObject.NULL)
        put("segment_duration_ms", segmentDurationMs)
        put("legacy", legacy)
        if (sourceOpus != null) put("source_opus", sourceOpus)
        put("audio", JSONObject().apply {
            put("codec", "opus_raw")
            put("sample_rate_hz", 16000)
            put("channels", 2)
            put("frame_duration_ms", 20)
            put("frame_size_bytes", 80)
            put("segments", JSONArray().apply {
                segments.forEach { s ->
                    put(JSONObject().apply {
                        put("index", s.index)
                        put("file", s.file)
                        put("start_ms", s.startMs)
                        put("end_ms", s.endMs)
                        put("bytes", s.bytes)
                        put("duration_ms", s.durationMs)
                    })
                }
            })
        })
        put("context", JSONObject().apply {
            put("file", SessionPaths.CONTEXT_FILE)
        })
        put("vad", JSONObject().apply {
            put("prelabel_file", vad.prelabelFile)
            put("status", vad.status)
            put("speech_segments", vad.speechSegments)
            put("total_segments", vad.totalSegments)
        })
    }

    companion object {
        fun load(file: File): SessionManifest? = try {
            val j = JSONObject(file.readText())
            val audio = j.getJSONObject("audio")
            val segArr = audio.getJSONArray("segments")
            val segments = mutableListOf<AudioSegmentEntry>()
            for (i in 0 until segArr.length()) {
                val s = segArr.getJSONObject(i)
                segments.add(
                    AudioSegmentEntry(
                        index = s.getInt("index"),
                        file = s.getString("file"),
                        startMs = s.getLong("start_ms"),
                        endMs = s.getLong("end_ms"),
                        bytes = s.getLong("bytes"),
                        durationMs = s.getLong("duration_ms")
                    )
                )
            }
            val vadJ = j.optJSONObject("vad")
            SessionManifest(
                formatVersion = j.optInt("format_version", 1),
                sessionId = j.getString("session_id"),
                deviceName = j.getString("device_name"),
                deviceAddress = j.optString("device_address").takeIf { it.isNotEmpty() },
                startedAtMs = j.getLong("started_at_ms"),
                endedAtMs = j.optLong("ended_at_ms").takeIf { j.has("ended_at_ms") && !j.isNull("ended_at_ms") },
                segmentDurationMs = j.optLong("segment_duration_ms", SessionPaths.SEGMENT_DURATION_MS),
                segments = segments,
                vad = VadSummary(
                    prelabelFile = vadJ?.optString("prelabel_file") ?: SessionPaths.VAD_PRELABEL_FILE,
                    status = vadJ?.optString("status") ?: "pending",
                    speechSegments = vadJ?.optInt("speech_segments") ?: 0,
                    totalSegments = vadJ?.optInt("total_segments") ?: segments.size
                ),
                legacy = j.optBoolean("legacy", false),
                sourceOpus = j.optString("source_opus").takeIf { it.isNotEmpty() }
            )
        } catch (_: Exception) {
            null
        }
    }
}

object SessionManifestIO {
    fun write(sessionDir: File, manifest: SessionManifest) {
        sessionDir.mkdirs()
        File(sessionDir, SessionPaths.AUDIO_DIR).mkdirs()
        File(sessionDir, "context").mkdirs()
        File(sessionDir, "labels").mkdirs()
        SessionPaths.manifestFile(sessionDir).writeTextAtomic(manifest.toJson().toString(2))
    }

    fun updateVadSummary(sessionDir: File, status: String, speechSegments: Int, totalSegments: Int) {
        val mf = SessionPaths.manifestFile(sessionDir)
        val manifest = SessionManifest.load(mf) ?: return
        manifest.vad = VadSummary(status = status, speechSegments = speechSegments, totalSegments = totalSegments)
        write(sessionDir, manifest)
    }
}
