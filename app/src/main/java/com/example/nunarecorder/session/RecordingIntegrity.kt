package com.example.nunarecorder.session

import com.example.nunarecorder.ble.AssembledFrame
import com.example.nunarecorder.ble.OpusStreamAssembler
import org.json.JSONArray
import org.json.JSONObject

/**
 * 段内一处空洞。粒度是「第几帧之后丢了几帧」，配合 [nextFrameId] 可以和设备侧日志对齐。
 */
data class FrameGap(
    /** 段内已收到多少**设备帧**之后出现空洞 */
    val atFrameOffset: Int,
    /** 缺失的**设备帧**数（不是 20 ms 包数） */
    val missingFrames: Int,
    /** 空洞之后第一帧的设备 frameId（u16） */
    val nextFrameId: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("at_frame_offset", atFrameOffset)
        put("missing_frames", missingFrames)
        put("next_frame_id", nextFrameId)
    }

    companion object {
        fun fromJson(o: JSONObject) = FrameGap(
            atFrameOffset = o.optInt("at_frame_offset"),
            missingFrames = o.optInt("missing_frames"),
            nextFrameId = o.optInt("next_frame_id")
        )
    }
}

/**
 * 一个分段的帧账目。
 *
 * **两个单位必须分开，不能混。** 2026-08-03 的真实会话暴露了这个错误：
 * 设备的一个 `frameId` 携带约 36 个 20 ms Opus 包（≈720 ms 音频），
 * 而旧实现拿「设备帧数」去比「按墙钟算的 20 ms 包数」，于是每段记成
 * `received=82 / expected=3000`，汇总出「97% 设备没发」——实际 97.3% 都收到了。
 * 这种统计比没有更糟：研究者会照着它得出完全相反的结论。
 *
 * 所以：
 * - [expectedPackets] / [receivedPackets] 是 **20 ms Opus 包**，和墙钟可比，是完整度的唯一依据。
 *   [receivedPackets] 由实际写入字节数反推，不依赖对设备打包方式的任何假设。
 * - [deviceFrames] / [deviceSequenceLost] 是 **设备打包单位**，只作诊断。
 */
data class SegmentFrameStats(
    /** 按墙钟应有的 20 ms 包数 */
    val expectedPackets: Int,
    /** 实际写入的 20 ms 包数（= 字节数 / 80） */
    val receivedPackets: Int,
    /** 收到的设备帧数（每个含多个 20 ms 包），仅诊断 */
    val deviceFrames: Int = 0,
    /** 设备 frameId 序号空洞，单位是**设备帧**，仅诊断 */
    val deviceSequenceLost: Int = 0,
    val gaps: List<FrameGap> = emptyList()
) {
    val missingPackets: Int get() = expectedPackets - receivedPackets

    /** 0..1；expectedPackets 为 0 时返回 1（没期望就没缺失） */
    val completeness: Float
        get() = if (expectedPackets <= 0) 1f
        else (receivedPackets.toFloat() / expectedPackets).coerceIn(0f, 1f)

    fun toJson(): JSONObject = JSONObject().apply {
        put("unit", "opus_packet_20ms")
        put("expected", expectedPackets)
        put("received", receivedPackets)
        put("missing", missingPackets)
        put("device_frames", JSONObject().apply {
            put("received", deviceFrames)
            put("sequence_lost", deviceSequenceLost)
            put("gaps", JSONArray().apply { gaps.forEach { put(it.toJson()) } })
        })
    }

    companion object {
        fun fromJson(o: JSONObject?): SegmentFrameStats? {
            if (o == null) return null
            val dev = o.optJSONObject("device_frames")
            val gapsArr = dev?.optJSONArray("gaps") ?: JSONArray()
            return SegmentFrameStats(
                expectedPackets = o.optInt("expected"),
                receivedPackets = o.optInt("received"),
                deviceFrames = dev?.optInt("received") ?: 0,
                deviceSequenceLost = dev?.optInt("sequence_lost") ?: 0,
                gaps = (0 until gapsArr.length()).map { FrameGap.fromJson(gapsArr.getJSONObject(it)) }
            )
        }

        /** 按墙钟时长换算应有的 20 ms 包数 */
        fun expectedPacketsFor(durationMs: Long): Int =
            (durationMs / OpusStreamAssembler.FRAME_DURATION_MS).toInt()
    }
}

/**
 * 边收帧边记账。每开一个新分段建一个。
 *
 * 期望包数在**封口时**才传入（[snapshot]）：最后一段通常不满 60 秒，
 * 按整段算会凭空多出一堆"丢失"。
 */
class SegmentFrameAccumulator {
    private var packets = 0
    private var deviceFrames = 0
    private var sequenceLost = 0
    private val gaps = mutableListOf<FrameGap>()

    val receivedPackets: Int get() = packets

    fun onFrame(frame: AssembledFrame) {
        if (frame.missingBefore > 0) {
            gaps.add(
                FrameGap(
                    atFrameOffset = deviceFrames,
                    missingFrames = frame.missingBefore,
                    nextFrameId = frame.frameId
                )
            )
            sequenceLost += frame.missingBefore
        }
        deviceFrames++
        // 用字节反推包数：不依赖对设备打包方式的任何假设
        packets += frame.opus.size / OpusStreamAssembler.OPUS_FRAME_SIZE
    }

    fun snapshot(expectedPackets: Int): SegmentFrameStats = SegmentFrameStats(
        expectedPackets = expectedPackets,
        receivedPackets = packets,
        deviceFrames = deviceFrames,
        deviceSequenceLost = sequenceLost,
        gaps = gaps.toList()
    )
}

/**
 * 参与者主动删掉的一分钟。
 *
 * 必须和 `missing_segments`（链路中断没采到）分开记：前者是知情同意事实，
 * 后者是数据质量问题。都表现为分段序号空洞，混在一起就再也分不清了。
 */
data class SegmentDeletion(
    val index: Int,
    /** 绝对 epoch 毫秒 */
    val deletedAtMs: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("index", index)
        put("deleted_at_ms", deletedAtMs)
    }

    companion object {
        fun fromJson(o: JSONObject) = SegmentDeletion(
            index = o.optInt("index"),
            deletedAtMs = o.optLong("deleted_at_ms")
        )
    }
}

/**
 * 一次链路中断。**时间是绝对 epoch 毫秒**，不是相对会话开始的偏移——
 * segment 的 `start_ms` 是相对偏移，服务端曾把它当 epoch 用，整批数据落到 1970-01-01
 * （见 doc/status/2026-07-31-field-test.md §1.1）。字段名带 `_at_ms` 以示区分。
 */
data class LinkGapEntry(
    val startAtMs: Long,
    /** null = 会话结束时链路仍未恢复 */
    val endAtMs: Long?,
    val reason: String,
    val reconnectAttempts: Int
) {
    val downMs: Long? get() = endAtMs?.let { it - startAtMs }

    /**
     * 这条是**我方主动造成的**空档，不是链路故障。
     *
     * 两者都表现为"这段时间没有音频"，所以共用 `events`；但把它们混在一起计数
     * 会得出假的断连率——实测一个整点会话 41 条 events 里 35 条是毫米波开关，
     * 真实断连只有 6 次。参见 [LinkHealth.disconnectCount]。
     */
    val intentional: Boolean get() = reason in INTENTIONAL_REASONS

    fun toJson(): JSONObject = JSONObject().apply {
        put("start_at_ms", startAtMs)
        put("end_at_ms", endAtMs ?: JSONObject.NULL)
        put("reason", reason)
        put("reconnect_attempts", reconnectAttempts)
        put("down_ms", downMs ?: JSONObject.NULL)
        // 只加不改：下游不必靠字符串匹配 reason 就能把主动扰动排除掉
        put("intentional", intentional)
    }

    companion object {
        /** 毫米波开关瞬间会干扰几帧音频 */
        const val REASON_MMWAVE_TOGGLE = "mmwave_toggle"

        /** 录声纹期间日常采集暂停 */
        const val REASON_VOICEPRINT = "voiceprint_capture"

        val INTENTIONAL_REASONS = setOf(REASON_MMWAVE_TOGGLE, REASON_VOICEPRINT)

        fun fromJson(o: JSONObject) = LinkGapEntry(
            startAtMs = o.optLong("start_at_ms"),
            endAtMs = if (o.isNull("end_at_ms")) null else o.optLong("end_at_ms"),
            reason = o.optString("reason"),
            reconnectAttempts = o.optInt("reconnect_attempts")
        )
    }
}

/**
 * 会话级链路健康摘要，写进 manifest 的 `link`。
 * 研究者不该从「这段没数据」去猜发生了什么。
 */
data class LinkHealth(
    val events: List<LinkGapEntry> = emptyList(),
    val resyncSkippedBytes: Int = 0,
    val incompleteFrames: Int = 0,
    val duplicateFrames: Int = 0,
    val reorderedFrames: Int = 0,
    val droppedCarryOverBytes: Int = 0
) {
    /** 真正的链路故障，**不含**毫米波开关和录声纹这类主动空档 */
    val disconnects: List<LinkGapEntry> get() = events.filterNot { it.intentional }

    /** 我方主动造成的空档 */
    val intentionalGaps: List<LinkGapEntry> get() = events.filter { it.intentional }

    /**
     * 断连次数。**排除主动空档**——2026-08-14 之前它是 `events.size`，
     * 于是移植毫米波之后每小时凭空多出三十几次"断连"：实测一个整点会话
     * 报了 41 次，真实断连是 6 次。用户当时的反馈正是"断联频率好像高了不少"，
     * 而同一批数据的音频覆盖率反而从 77% 涨到 96%——**数字在撒谎，链路其实变好了**。
     *
     * 这是本项目最在意的一类问题：不是没实现，是实现了但会安静地报出假事实。
     */
    val disconnectCount: Int get() = disconnects.size

    /** 链路真正断开的总时长。声纹暂停有两分钟的真实时长，算进去会虚报链路故障 */
    val totalDownMs: Long get() = disconnects.sumOf { it.downMs ?: 0L }

    fun toJson(): JSONObject = JSONObject().apply {
        put("disconnect_count", disconnectCount)
        put("total_down_ms", totalDownMs)
        // 只加不改：主动空档仍然全部留在 events 里，这里只是给出它们的计数，
        // 免得下游把 events.size 当断连数（我们自己就犯过）
        put("intentional_gap_count", intentionalGaps.size)
        put("events", JSONArray().apply { events.forEach { put(it.toJson()) } })
        put("assembler", JSONObject().apply {
            put("resync_skipped_bytes", resyncSkippedBytes)
            put("incomplete_frames", incompleteFrames)
            put("duplicate_frames", duplicateFrames)
            put("reordered_frames", reorderedFrames)
            put("dropped_carry_over_bytes", droppedCarryOverBytes)
        })
    }

    companion object {
        fun fromJson(o: JSONObject?): LinkHealth {
            if (o == null) return LinkHealth()
            val arr = o.optJSONArray("events") ?: JSONArray()
            val a = o.optJSONObject("assembler")
            return LinkHealth(
                events = (0 until arr.length()).map { LinkGapEntry.fromJson(arr.getJSONObject(it)) },
                resyncSkippedBytes = a?.optInt("resync_skipped_bytes") ?: 0,
                incompleteFrames = a?.optInt("incomplete_frames") ?: 0,
                duplicateFrames = a?.optInt("duplicate_frames") ?: 0,
                reorderedFrames = a?.optInt("reordered_frames") ?: 0,
                droppedCarryOverBytes = a?.optInt("dropped_carry_over_bytes") ?: 0
            )
        }
    }
}
