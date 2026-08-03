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

    fun toJson(): JSONObject = JSONObject().apply {
        put("start_at_ms", startAtMs)
        put("end_at_ms", endAtMs ?: JSONObject.NULL)
        put("reason", reason)
        put("reconnect_attempts", reconnectAttempts)
        put("down_ms", downMs ?: JSONObject.NULL)
    }

    companion object {
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
    val disconnectCount: Int get() = events.size
    val totalDownMs: Long get() = events.sumOf { it.downMs ?: 0L }

    fun toJson(): JSONObject = JSONObject().apply {
        put("disconnect_count", disconnectCount)
        put("total_down_ms", totalDownMs)
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
