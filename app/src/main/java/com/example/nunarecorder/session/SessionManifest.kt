package com.example.nunarecorder.session

import com.example.nunarecorder.util.writeTextAtomic
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class AudioSegmentEntry(
    val index: Int,
    val file: String,
    /** **相对会话开始的偏移**，不是 epoch。绝对时间 = `manifest.started_at_ms + start_ms` */
    val startMs: Long,
    val endMs: Long,
    val bytes: Long,
    val durationMs: Long,
    /** 帧账目；旧会话没有这一段，为 null */
    val frames: SegmentFrameStats? = null
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
    val sourceOpus: String? = null,
    /** 录制进行中（用于 UI 实时展示） */
    var recordingActive: Boolean = false,
    var openSegmentIndex: Int? = null,
    var openSegmentBytes: Long = 0L,
    /** 链路中断区间与重组器诊断；研究者不该从"这段没数据"去猜发生了什么 */
    var link: LinkHealth = LinkHealth(),
    /**
     * 完全没有音频的分段序号。分段序号由墙钟推导，所以 `segments[]` 里会出现空洞，
     * 这里把空洞显式列出来，而不是让它看起来像会话本来就短。
     */
    var missingSegments: List<Int> = emptyList(),
    /**
     * 采集端版本，例如 `1.1 (3)`。
     *
     * 没有它就无法回答"这批数据是哪个 APK 采的"——而我们几乎每天都在改
     * 分段、丢帧记账和 BLE 重连的行为。08-03 那批 manifest 里的 `frames` 用的还是
     * 错的量纲，如果当时带了版本号，事后一眼就能筛出来。
     */
    var appVersion: String? = null,
    /** 参与者主动删除的分段；与 [missingSegments] 是两回事，不能混 */
    var deletedSegments: List<SegmentDeletion> = emptyList()
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
        appVersion?.let { put("app_version", it) }
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
                        s.frames?.let { put("frames", it.toJson()) }
                    })
                }
            })
            put("missing_segments", JSONArray().apply { missingSegments.forEach { put(it) } })
            put("deleted_segments", JSONArray().apply { deletedSegments.forEach { put(it.toJson()) } })
        })
        put("link", link.toJson())
        put("context", JSONObject().apply {
            put("file", SessionPaths.CONTEXT_FILE)
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
                        frames = SegmentFrameStats.fromJson(s.optJSONObject("frames"))
                    )
                )
            }
            val missingArr = audio.optJSONArray("missing_segments") ?: JSONArray()
            val missing = (0 until missingArr.length()).map { missingArr.getInt(it) }
            val deletedArr = audio.optJSONArray("deleted_segments") ?: JSONArray()
            val deleted = (0 until deletedArr.length())
                .map { SegmentDeletion.fromJson(deletedArr.getJSONObject(it)) }
            val vadJ = j.optJSONObject("vad")
            val recJ = j.optJSONObject("recording")
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
                recordingActive = recJ?.optBoolean("active") == true,
                openSegmentIndex = recJ?.optInt("open_segment_index", -1)?.takeIf { it >= 0 },
                openSegmentBytes = recJ?.optLong("open_segment_bytes") ?: 0L,
                link = LinkHealth.fromJson(j.optJSONObject("link")),
                missingSegments = missing,
                appVersion = j.optString("app_version").takeIf { it.isNotEmpty() },
                deletedSegments = deleted
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
