package com.example.nunarecorder.session

import com.example.nunarecorder.ble.AssembledFrame
import com.example.nunarecorder.ble.OpusStreamAssembler
import org.json.JSONArray
import org.json.JSONObject

/**
 * 段内一处空洞。粒度是「第几帧之后丢了几帧」，配合 [nextFrameId] 可以和设备侧日志对齐。
 */
data class FrameGap(
    /** 段内已收到多少帧之后出现空洞 */
    val atFrameOffset: Int,
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
 * 区分两类缺失，因为它们的归属完全不同：
 *
 * - [sequenceLostFrames]：设备 `frameId` 序号上有洞，说明帧发出来了但没到（BLE 链路丢包）。
 * - [unaccountedFrames]：序号连续，但按墙钟本该有的帧数没凑够，说明设备根本没发那么多帧
 *   （设备侧节流、时钟漂移，或链路整段断开）。
 *
 * 2026-07-31 实测每段稳定只有 59.04 秒音频。这两个数字能直接回答「该找 Ruihan 还是该改 App」。
 * 可能为负（设备发得比墙钟快），如实保留符号，不做钳制。
 */
data class SegmentFrameStats(
    val expectedFrames: Int,
    val receivedFrames: Int,
    val sequenceLostFrames: Int,
    val gaps: List<FrameGap> = emptyList()
) {
    val unaccountedFrames: Int get() = expectedFrames - receivedFrames - sequenceLostFrames

    fun toJson(): JSONObject = JSONObject().apply {
        put("expected", expectedFrames)
        put("received", receivedFrames)
        put("sequence_lost", sequenceLostFrames)
        put("unaccounted", unaccountedFrames)
        put("gaps", JSONArray().apply { gaps.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject?): SegmentFrameStats? {
            if (o == null) return null
            val gapsArr = o.optJSONArray("gaps") ?: JSONArray()
            val gaps = (0 until gapsArr.length()).map { FrameGap.fromJson(gapsArr.getJSONObject(it)) }
            return SegmentFrameStats(
                expectedFrames = o.optInt("expected"),
                receivedFrames = o.optInt("received"),
                sequenceLostFrames = o.optInt("sequence_lost"),
                gaps = gaps
            )
        }

        /** 按墙钟时长换算应有帧数（20 ms 一帧） */
        fun expectedFramesFor(durationMs: Long): Int =
            (durationMs / OpusStreamAssembler.FRAME_DURATION_MS).toInt()
    }
}

/**
 * 边收帧边记账。每开一个新分段建一个。
 *
 * 期望帧数在**封口时**才传入（[snapshot]）：最后一段通常不满 60 秒，
 * 按整段算会凭空多出一堆"丢失"。
 */
class SegmentFrameAccumulator {
    private var received = 0
    private var sequenceLost = 0
    private val gaps = mutableListOf<FrameGap>()

    val receivedFrames: Int get() = received
    val sequenceLostFrames: Int get() = sequenceLost

    fun onFrame(frame: AssembledFrame) {
        if (frame.missingBefore > 0) {
            gaps.add(
                FrameGap(
                    atFrameOffset = received,
                    missingFrames = frame.missingBefore,
                    nextFrameId = frame.frameId
                )
            )
            sequenceLost += frame.missingBefore
        }
        received++
    }

    fun snapshot(expectedFrames: Int): SegmentFrameStats = SegmentFrameStats(
        expectedFrames = expectedFrames,
        receivedFrames = received,
        sequenceLostFrames = sequenceLost,
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
