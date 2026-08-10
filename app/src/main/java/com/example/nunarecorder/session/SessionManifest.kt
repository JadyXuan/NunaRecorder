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
    val durationMs: Long,
    val integrityOk: Boolean = true,
    val integrityIssue: String? = null
)

data class VadSummary(
    val prelabelFile: String = SessionPaths.VAD_PRELABEL_FILE,
    val status: String = "pending",
    val speechSegments: Int = 0,
    val totalSegments: Int = 0
)

data class MmWaveSummary(
    val enabled: Boolean = false,
    val status: String = STATUS_DISABLED,
    val file: String = SessionPaths.MMWAVE_FILE,
    val packetCount: Long? = null,
    val payloadBytes: Long? = null,
    val fileBytes: Long? = null,
    val malformedPackets: Long = 0L,
    val droppedPackets: Long = 0L,
    val stateFile: String = SessionPaths.MMWAVE_STATE_FILE,
    val stateEventCount: Long = 0L,
    val stateFileBytes: Long? = null
) {
    companion object {
        const val STATUS_DISABLED = "disabled"
        const val STATUS_WAITING = "waiting"
        const val STATUS_CAPTURED = "captured"
        const val STATUS_NO_DATA = "no_data"
        const val STATUS_FINALIZE_TIMEOUT = "finalize_timeout"

        fun initial(enabled: Boolean): MmWaveSummary = MmWaveSummary(
            enabled = enabled,
            status = if (enabled) STATUS_WAITING else STATUS_DISABLED
        )
    }
}

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
    val sourceOpus: String? = null,
    val contextModalities: List<String> = SessionModalities.forRecording(false),
    var mmWave: MmWaveSummary = MmWaveSummary.initial(
        SessionModalities.MMWAVE in contextModalities
    ),
    /** 录制进行中（用于 UI 实时展示） */
    var recordingActive: Boolean = false,
    var openSegmentIndex: Int? = null,
    var openSegmentBytes: Long = 0L
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
            put("channels", 1)
            put("frame_duration_ms", 20)
            put("frame_size_bytes", 80)
            put("timeline_file", SessionPaths.AUDIO_TIMELINE_FILE)
            put("segments", JSONArray().apply {
                segments.forEach { s ->
                    put(JSONObject().apply {
                        put("index", s.index)
                        put("file", s.file)
                        put("start_ms", s.startMs)
                        put("end_ms", s.endMs)
                        put("bytes", s.bytes)
                        put("duration_ms", s.durationMs)
                        put("integrity_ok", s.integrityOk)
                        if (s.integrityIssue != null) {
                            put("integrity_issue", s.integrityIssue)
                        }
                    })
                }
            })
        })
        put("context", JSONObject().apply {
            put("file", SessionPaths.CONTEXT_FILE)
            put("modalities", JSONArray(contextModalities))
            if (SessionModalities.MMWAVE in contextModalities) {
                put("mmwave_file", SessionPaths.MMWAVE_FILE)
                put("mmwave_state_file", SessionPaths.MMWAVE_STATE_FILE)
            }
            put("mmwave", JSONObject().apply {
                put("enabled", mmWave.enabled)
                put("status", mmWave.status)
                put("file", mmWave.file)
                mmWave.packetCount?.let { put("packet_count", it) }
                mmWave.payloadBytes?.let { put("payload_bytes", it) }
                mmWave.fileBytes?.let { put("file_bytes", it) }
                put("malformed_packets", mmWave.malformedPackets)
                put("dropped_packets", mmWave.droppedPackets)
                put("state_file", mmWave.stateFile)
                put("state_event_count", mmWave.stateEventCount)
                mmWave.stateFileBytes?.let { put("state_file_bytes", it) }
            })
        })
        put("vad", JSONObject().apply {
            put("prelabel_file", vad.prelabelFile)
            put("status", vad.status)
            put("speech_segments", vad.speechSegments)
            put("total_segments", vad.totalSegments)
        })
        if (recordingActive) {
            put("recording", JSONObject().apply {
                put("active", true)
                put("open_segment_index", openSegmentIndex ?: JSONObject.NULL)
                put("open_segment_bytes", openSegmentBytes)
            })
        }
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
                        durationMs = s.getLong("duration_ms"),
                        integrityOk = s.optBoolean("integrity_ok", true),
                        integrityIssue = s.optString("integrity_issue")
                            .takeIf { it.isNotBlank() }
                    )
                )
            }
            val vadJ = j.optJSONObject("vad")
            val recJ = j.optJSONObject("recording")
            val contextJ = j.optJSONObject("context")
            val modalitiesJ = contextJ?.optJSONArray("modalities")
            val contextModalities = if (modalitiesJ == null) {
                SessionModalities.forRecording(false)
            } else {
                buildList {
                    for (i in 0 until modalitiesJ.length()) {
                        modalitiesJ.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }.ifEmpty { SessionModalities.forRecording(false) }
            }
            val mmWaveJ = contextJ?.optJSONObject("mmwave")
            val mmWaveEnabled = mmWaveJ?.optBoolean("enabled")
                ?: (SessionModalities.MMWAVE in contextModalities)
            val mmWaveFile = File(file.parentFile, SessionPaths.MMWAVE_FILE)
            val inferredMmWaveStatus = when {
                !mmWaveEnabled -> MmWaveSummary.STATUS_DISABLED
                mmWaveFile.exists() && mmWaveFile.length() > 0L -> MmWaveSummary.STATUS_CAPTURED
                recJ?.optBoolean("active") == true -> MmWaveSummary.STATUS_WAITING
                else -> MmWaveSummary.STATUS_NO_DATA
            }
            val mmWaveSummary = MmWaveSummary(
                enabled = mmWaveEnabled,
                status = mmWaveJ?.optString("status")?.takeIf { it.isNotBlank() }
                    ?: inferredMmWaveStatus,
                file = mmWaveJ?.optString("file")?.takeIf { it.isNotBlank() }
                    ?: SessionPaths.MMWAVE_FILE,
                packetCount = mmWaveJ?.optLongOrNull("packet_count"),
                payloadBytes = mmWaveJ?.optLongOrNull("payload_bytes"),
                fileBytes = mmWaveJ?.optLongOrNull("file_bytes")
                    ?: mmWaveFile.takeIf { it.exists() }?.length(),
                malformedPackets = mmWaveJ?.optLong("malformed_packets", 0L) ?: 0L,
                droppedPackets = mmWaveJ?.optLong("dropped_packets", 0L) ?: 0L,
                stateFile = mmWaveJ?.optString("state_file")?.takeIf { it.isNotBlank() }
                    ?: SessionPaths.MMWAVE_STATE_FILE,
                stateEventCount = mmWaveJ?.optLong("state_event_count", 0L) ?: 0L,
                stateFileBytes = mmWaveJ?.optLongOrNull("state_file_bytes")
            )
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
                sourceOpus = j.optString("source_opus").takeIf { it.isNotEmpty() },
                contextModalities = contextModalities,
                mmWave = mmWaveSummary,
                recordingActive = recJ?.optBoolean("active") == true,
                openSegmentIndex = recJ?.optInt("open_segment_index", -1)?.takeIf { it >= 0 },
                openSegmentBytes = recJ?.optLong("open_segment_bytes") ?: 0L
            )
        } catch (_: Exception) {
            null
        }
    }
}

private fun JSONObject.optLongOrNull(name: String): Long? =
    if (has(name) && !isNull(name)) optLong(name) else null

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
