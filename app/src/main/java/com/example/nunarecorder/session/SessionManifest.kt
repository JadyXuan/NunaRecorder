package com.example.nunarecorder.session

import com.example.nunarecorder.util.writeTextAtomic
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
 * 毫米波采集摘要。移植自上游 `signed-test/mmwave-timeline-beta9`（Ruihan 真机验证）。
 *
 * Nuna 固件自己控制毫米波 **30 秒开 / 30 秒关**（省电），不是连续的；
 * 开关瞬间还会短暂干扰几帧音频。所以除了数据本身，开关时间线
 * （[SessionPaths.MMWAVE_STATE_FILE]）也必须记，否则事后做跨模态切片对齐时
 * 那几帧缺失会被当成掉线。
 */
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
    /**
     * 设备固件版本，例如 `3.14.5.1813`；读不到时为 null。
     *
     * 2026-08-08：一台设备「用不了」，最后靠升级固件（1811 → 1813）解决。
     * 固件版本会决定一台设备能不能采到数据，而数据里原来看不出来是哪个版本采的。
     */
    var deviceFirmware: String? = null,
    /**
     * 会话是怎么结束的。null = 旧会话，没有这个字段。
     *
     * 2026-08-09 全天数据里有 6 个零长会话（整点轮转崩掉留下的），而 manifest 上
     * **看不出那是崩溃还是一次正常的零长会话**——唯一能区分的是 `vad.status` 停在
     * `pending`，那纯属碰巧。读数据的人不该靠碰巧的旁证去猜。
     *
     * 取值：[END_USER_STOP]（用户按了停止）、[END_ROLLOVER]（整点换会话）、
     * [END_CRASH_RECOVERED]（上次没能正常收尾，下次启动时补的）。
     */
    var endReason: String? = null,
    /** 本次会话启用的上下文模态；**新增字段**，旧会话读回来是默认三模态 */
    var contextModalities: List<String> = SessionModalities.forRecording(false),
    /** 毫米波摘要；**新增字段**，不发也不影响服务端 */
    var mmWave: MmWaveSummary = MmWaveSummary.initial(false),
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
        endReason?.let { put("end_reason", it) }
        deviceFirmware?.let { put("device_firmware", it) }
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
            // 只加不改：既有的 "file" 原样保留，读旧 manifest 的服务端不受影响
            put("modalities", JSONArray(contextModalities))
            if (SessionModalities.MMWAVE in contextModalities) {
                put("mmwave_file", SessionPaths.MMWAVE_FILE)
                put("mmwave_state_file", SessionPaths.MMWAVE_STATE_FILE)
            }
        })
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
        const val END_USER_STOP = "user_stop"
        const val END_ROLLOVER = "hourly_rollover"
        const val END_CRASH_RECOVERED = "crash_recovered"
        /** 服务被销毁（系统回收 / OEM 省电），不是用户按的停止 */
        const val END_SERVICE_DESTROYED = "service_destroyed"

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
                endReason = j.optString("end_reason").takeIf { it.isNotEmpty() },
                contextModalities = j.optJSONObject("context")?.optJSONArray("modalities")
                    ?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            arr.optString(i).takeIf { it.isNotBlank() }
                        }
                    }?.ifEmpty { null } ?: SessionModalities.forRecording(false),
                mmWave = j.optJSONObject("mmwave")?.let { mw ->
                    MmWaveSummary(
                        enabled = mw.optBoolean("enabled"),
                        status = mw.optString("status", MmWaveSummary.STATUS_DISABLED),
                        file = mw.optString("file", SessionPaths.MMWAVE_FILE),
                        packetCount = mw.optLong("packet_count").takeIf { mw.has("packet_count") },
                        payloadBytes = mw.optLong("payload_bytes").takeIf { mw.has("payload_bytes") },
                        fileBytes = mw.optLong("file_bytes").takeIf { mw.has("file_bytes") },
                        malformedPackets = mw.optLong("malformed_packets"),
                        droppedPackets = mw.optLong("dropped_packets"),
                        stateFile = mw.optString("state_file", SessionPaths.MMWAVE_STATE_FILE),
                        stateEventCount = mw.optLong("state_event_count"),
                        stateFileBytes = mw.optLong("state_file_bytes")
                            .takeIf { mw.has("state_file_bytes") }
                    )
                } ?: MmWaveSummary.initial(false),
                deviceFirmware = j.optString("device_firmware").takeIf { it.isNotEmpty() },
                deletedSegments = deleted
            )
        } catch (_: Exception) {
            null
        }
    }
}

object SessionManifestIO {
    private val sessionLocks = ConcurrentHashMap<String, Any>()

    /** Serialize a read-modify-write operation for one session manifest. */
    fun <T> withSessionLock(sessionDir: File, block: () -> T): T {
        val lock = sessionLocks.computeIfAbsent(sessionDir.absolutePath) { Any() }
        return synchronized(lock, block)
    }

    fun write(sessionDir: File, manifest: SessionManifest) {
        withSessionLock(sessionDir) {
            sessionDir.mkdirs()
            File(sessionDir, SessionPaths.AUDIO_DIR).mkdirs()
            File(sessionDir, "context").mkdirs()
            File(sessionDir, "labels").mkdirs()
            SessionPaths.manifestFile(sessionDir).writeTextAtomic(manifest.toJson().toString(2))
        }
    }

    fun updateVadSummary(sessionDir: File, status: String, speechSegments: Int, totalSegments: Int) {
        withSessionLock(sessionDir) {
            val mf = SessionPaths.manifestFile(sessionDir)
            val manifest = SessionManifest.load(mf) ?: return@withSessionLock
            manifest.vad = VadSummary(status = status, speechSegments = speechSegments, totalSegments = totalSegments)
            write(sessionDir, manifest)
        }
    }
}
