package com.example.nunarecorder.ble

/**
 * 一个完整的 Opus 帧，以及它与上一帧之间的设备序号空洞。
 *
 * @param frameId 设备侧 `frameId`（u16，会在 65536 处回绕）
 * @param opus 裸 Opus 负载，长度已裁剪为 [OpusStreamAssembler.OPUS_FRAME_SIZE] 的整数倍
 * @param missingBefore 本帧与上一帧之间缺失的帧数；0 表示序号连续
 */
data class AssembledFrame(
    val frameId: Int,
    val opus: ByteArray,
    val missingBefore: Int
) {
    override fun equals(other: Any?): Boolean =
        other is AssembledFrame &&
            frameId == other.frameId &&
            missingBefore == other.missingBefore &&
            opus.contentEquals(other.opus)

    override fun hashCode(): Int =
        (frameId * 31 + missingBefore) * 31 + opus.contentHashCode()
}

/**
 * 解码器自身的诊断计数，与「每段丢了多少」是两回事：
 * 这里记录的是**为什么**会丢，用于判断问题在链路层还是设备侧。
 */
data class AssemblerDiagnostics(
    /** 成功拼齐并输出的帧数 */
    val emittedFrames: Int = 0,
    /** 设备序号回退（乱序到达），未计入丢失 */
    val reorderedFrames: Int = 0,
    /** 同一个 frameId 被输出两次 */
    val duplicateFrames: Int = 0,
    /** 收到过部分 chunk 但一直没凑齐，被淘汰的帧 */
    val incompleteFrames: Int = 0,
    /** 为了找回 0xAA 帧头而丢弃的字节数；持续非 0 说明链路在丢包或错帧 */
    val resyncSkippedBytes: Int = 0,
    /** 跨 notification 携带、最终没能凑成完整消息而被丢弃的字节数 */
    val droppedCarryOverBytes: Int = 0
)

/**
 * 把 A003 notification 的字节流重组成 Opus 帧，并如实记录空洞。
 *
 * 与被它取代的旧实现的两点关键差异：
 *
 * 1. **跨 notification 的半条消息不再被丢掉。** 旧的 `BleAudioReassembler.feed()` 在
 *    `idx + msgTotalLen > totalLen` 时直接 `break`，注释写着「下次再解析」，但它没有任何
 *    跨调用缓冲，尾巴上那半条消息就此消失，下一个 notification 又从中间开始扫 0xAA。
 *    这里保留 [carryOver]，下次 feed 时拼回去。
 * 2. **丢帧不再是静默的。** 设备侧 `frameId` 是 u16 递增序号，据此可以精确算出
 *    「在第几帧之后丢了几帧」，而不是只看到文件短了一截。
 *
 * 本类**不做任何补偿**：不补零、不拉伸时间轴。段内音频短了就是短了，空洞位置如实上报，
 * 由 manifest 记录给下游。这是数据集时间轴的基础，宁可难看也不能含糊。
 *
 * 纯 Kotlin，无 Android 依赖，可直接跑 JVM 单测。
 */
class OpusStreamAssembler(
    /** 未凑齐的帧最多保留多少个；超出后淘汰最旧的，避免 16 小时会话里 map 无限增长 */
    private val maxPendingFrames: Int = 64,
    /** carryOver 上限；超过说明已经错帧，留着只会污染后续解析 */
    private val maxCarryOverBytes: Int = 2048
) {

    companion object {
        const val OPUS_FRAME_SIZE = 80
        const val FRAME_DURATION_MS = 20L

        private const val HEADER_MAGIC = 0xAA
        private const val AUDIO_TYPE = 0x10
        /** AA + type + len(2) + ver + checksum(2) */
        private const val MIN_HEADER_LEN = 7
        /** frameId(2) + frameSize(2) + chunkId(1) + totalChunks(1) + timestamp(8) */
        private const val MIN_AUDIO_BIZ_HEADER = 14
        /**
         * 负载长度上限。协议里一条消息含头不超过 512 字节，实际 BLE 消息在 110 字节量级。
         * 超过这个值几乎必然是把数据里的 0xAA 误当成了帧头——必须当作假帧头跳过，
         * 否则会把一个荒唐的 msgTotalLen 存进 carryOver，之后再也对不齐。
         */
        private const val MAX_PAYLOAD_LEN = 512

        /** u16 序号差值超过一半量程时按「回退/乱序」处理，而不是「丢了 6 万帧」 */
        private const val SEQUENCE_HALF_RANGE = 0x8000
        private const val SEQUENCE_MASK = 0xFFFF
    }

    private class PendingFrame(
        var totalChunks: Int,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf()
    )

    /** LinkedHashMap：插入序即淘汰序 */
    private val pending = LinkedHashMap<Int, PendingFrame>()
    private var carryOver: ByteArray = ByteArray(0)
    private var lastEmittedFrameId: Int? = null

    private var emittedFrames = 0
    private var reorderedFrames = 0
    private var duplicateFrames = 0
    private var incompleteFrames = 0
    private var resyncSkippedBytes = 0
    private var droppedCarryOverBytes = 0

    fun diagnostics(): AssemblerDiagnostics = AssemblerDiagnostics(
        emittedFrames = emittedFrames,
        reorderedFrames = reorderedFrames,
        duplicateFrames = duplicateFrames,
        incompleteFrames = incompleteFrames,
        resyncSkippedBytes = resyncSkippedBytes,
        droppedCarryOverBytes = droppedCarryOverBytes
    )

    /** 断连后重连调用：丢掉半条消息和未凑齐的帧，但**保留** [lastEmittedFrameId] 以便继续算空洞。 */
    fun onLinkInterrupted() {
        droppedCarryOverBytes += carryOver.size
        carryOver = ByteArray(0)
        incompleteFrames += pending.size
        pending.clear()
    }

    /** 会话结束/换设备时的完全复位。 */
    fun reset() {
        onLinkInterrupted()
        lastEmittedFrameId = null
    }

    /**
     * 喂入一个 BLE notification 的原始字节，返回本次拼齐的所有完整帧（到达顺序）。
     */
    fun feed(data: ByteArray): List<AssembledFrame> {
        val buffer = if (carryOver.isEmpty()) data else carryOver + data
        carryOver = ByteArray(0)

        val out = mutableListOf<AssembledFrame>()
        var idx = 0
        val total = buffer.size

        while (idx < total) {
            if ((buffer[idx].toInt() and 0xFF) != HEADER_MAGIC) {
                idx++
                resyncSkippedBytes++
                continue
            }
            if (idx + MIN_HEADER_LEN > total) break

            val length = readLeU16(buffer, idx + 2)
            if (length > MAX_PAYLOAD_LEN) {
                // 假帧头：跳过这个 0xAA 继续找，不要把它写进 carryOver
                idx++
                resyncSkippedBytes++
                continue
            }
            val msgTotalLen = MIN_HEADER_LEN + length
            if (idx + msgTotalLen > total) break

            val msgType = buffer[idx + 1].toInt() and 0xFF
            if (msgType == AUDIO_TYPE && length >= MIN_AUDIO_BIZ_HEADER) {
                parseAudioPayload(buffer, idx + MIN_HEADER_LEN, length)?.let { out.add(it) }
            }
            idx += msgTotalLen
        }

        if (idx < total) {
            val remaining = total - idx
            if (remaining <= maxCarryOverBytes) {
                carryOver = buffer.copyOfRange(idx, total)
            } else {
                droppedCarryOverBytes += remaining
            }
        }
        return out
    }

    private fun parseAudioPayload(buffer: ByteArray, offset: Int, length: Int): AssembledFrame? {
        val frameId = readLeU16(buffer, offset)
        val chunkId = buffer[offset + 4].toInt() and 0xFF
        val totalChunks = buffer[offset + 5].toInt() and 0xFF
        if (totalChunks <= 0) return null

        val opusChunk = buffer.copyOfRange(offset + MIN_AUDIO_BIZ_HEADER, offset + length)

        val frame = pending.getOrPut(frameId) { PendingFrame(totalChunks) }
        frame.totalChunks = maxOf(frame.totalChunks, totalChunks)
        frame.chunks[chunkId] = opusChunk

        evictOverflow()

        if (frame.chunks.size < frame.totalChunks) return null

        val ordered = (0 until frame.totalChunks).map { frame.chunks[it] ?: return null }
        pending.remove(frameId)

        val concatenated = ByteArray(ordered.sumOf { it.size })
        var pos = 0
        for (chunk in ordered) {
            chunk.copyInto(concatenated, pos)
            pos += chunk.size
        }
        val usable = concatenated.size - concatenated.size % OPUS_FRAME_SIZE
        if (usable <= 0) return null

        val missingBefore = advanceSequence(frameId)
        emittedFrames++
        return AssembledFrame(
            frameId = frameId,
            opus = if (usable == concatenated.size) concatenated else concatenated.copyOf(usable),
            missingBefore = missingBefore
        )
    }

    /** @return 与上一帧之间缺失的帧数 */
    private fun advanceSequence(frameId: Int): Int {
        val previous = lastEmittedFrameId
        if (previous == null) {
            lastEmittedFrameId = frameId
            return 0
        }
        val delta = (frameId - previous) and SEQUENCE_MASK
        return when {
            delta == 0 -> {
                duplicateFrames++
                0
            }
            delta >= SEQUENCE_HALF_RANGE -> {
                // 序号回退：乱序到达，不是丢失。不推进 lastEmittedFrameId，
                // 否则后面每一帧都会被算成一个巨大的空洞。
                reorderedFrames++
                0
            }
            else -> {
                lastEmittedFrameId = frameId
                delta - 1
            }
        }
    }

    private fun evictOverflow() {
        while (pending.size > maxPendingFrames) {
            val oldest = pending.keys.first()
            pending.remove(oldest)
            incompleteFrames++
        }
    }

    private fun readLeU16(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)
}
