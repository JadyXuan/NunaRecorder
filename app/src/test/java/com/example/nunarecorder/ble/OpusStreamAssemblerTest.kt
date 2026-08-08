package com.example.nunarecorder.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-07-31 真机实测每 60 秒分段只有 59.04 秒音频（稳定 1.6% 丢帧），且丢在哪没人知道。
 * 这些用例锁住两件事：拼包不能再吞掉跨 notification 的半条消息；丢帧必须被算出来。
 */
class OpusStreamAssemblerTest {

    private fun opus(seed: Int) = ByteArray(OpusStreamAssembler.OPUS_FRAME_SIZE) {
        ((seed + it) and 0xFF).toByte()
    }

    /** 组一条 A003 音频消息：AA | type | len(2 LE) | ver | checksum(2) | 业务负载 */
    private fun audioMessage(
        frameId: Int,
        opusChunk: ByteArray,
        chunkId: Int = 0,
        totalChunks: Int = 1
    ): ByteArray {
        val payload = ByteArray(14 + opusChunk.size)
        payload[0] = (frameId and 0xFF).toByte()
        payload[1] = ((frameId shr 8) and 0xFF).toByte()
        payload[2] = OpusStreamAssembler.OPUS_FRAME_SIZE.toByte()
        payload[3] = 0
        payload[4] = chunkId.toByte()
        payload[5] = totalChunks.toByte()
        // timestamp 6..13 留 0，重组不依赖它
        opusChunk.copyInto(payload, 14)

        val msg = ByteArray(7 + payload.size)
        msg[0] = 0xAA.toByte()
        msg[1] = 0x10 // AUDIO_RECORDING_DATA
        msg[2] = (payload.size and 0xFF).toByte()
        msg[3] = ((payload.size shr 8) and 0xFF).toByte()
        msg[4] = 1
        payload.copyInto(msg, 7)
        return msg
    }

    @Test
    fun `单条完整消息拼出一帧`() {
        val a = OpusStreamAssembler()
        val frames = a.feed(audioMessage(100, opus(1)))

        assertEquals(1, frames.size)
        assertEquals(100, frames[0].frameId)
        assertEquals(0, frames[0].missingBefore)
        assertArrayEquals(opus(1), frames[0].opus)
    }

    /**
     * 回归：旧实现在这里 `break` 掉就再也不管尾巴了，半条消息连同它后面那一帧一起消失。
     * 这是「每分钟稳定少 1 秒」最可能的自家责任。
     */
    @Test
    fun `跨 notification 切开的消息不会丢`() {
        val a = OpusStreamAssembler()
        val msg = audioMessage(7, opus(2))
        val cut = msg.size - 30

        val first = a.feed(msg.copyOfRange(0, cut))
        assertTrue("上半条不该产出帧", first.isEmpty())

        val second = a.feed(msg.copyOfRange(cut, msg.size))
        assertEquals(1, second.size)
        assertEquals(7, second[0].frameId)
        assertArrayEquals(opus(2), second[0].opus)
        assertEquals(0, a.diagnostics().resyncSkippedBytes)
    }

    @Test
    fun `一帧拆成两个 chunk 分别到达后才输出`() {
        val a = OpusStreamAssembler()
        val full = opus(3)
        val head = full.copyOfRange(0, 40)
        val tail = full.copyOfRange(40, 80)

        assertTrue(a.feed(audioMessage(9, head, chunkId = 0, totalChunks = 2)).isEmpty())
        val frames = a.feed(audioMessage(9, tail, chunkId = 1, totalChunks = 2))

        assertEquals(1, frames.size)
        assertArrayEquals(full, frames[0].opus)
    }

    @Test
    fun `frameId 空洞被算成缺失帧数`() {
        val a = OpusStreamAssembler()
        a.feed(audioMessage(10, opus(0)))
        // 11、12、13 丢了，下一帧直接是 14
        val frames = a.feed(audioMessage(14, opus(0)))

        assertEquals(1, frames.size)
        assertEquals(3, frames[0].missingBefore)
        assertEquals(14, frames[0].frameId)
    }

    /** frameId 是 u16，21.8 分钟就回绕一次；不能把 65535→0 当成丢了 65535 帧。 */
    @Test
    fun `序号回绕不产生虚假空洞`() {
        val a = OpusStreamAssembler()
        a.feed(audioMessage(65535, opus(0)))
        val frames = a.feed(audioMessage(0, opus(0)))

        assertEquals(1, frames.size)
        assertEquals(0, frames[0].missingBefore)
    }

    @Test
    fun `序号回退算乱序不算丢失`() {
        val a = OpusStreamAssembler()
        a.feed(audioMessage(50, opus(0)))
        val frames = a.feed(audioMessage(48, opus(0)))

        assertEquals(1, frames.size)
        assertEquals(0, frames[0].missingBefore)
        assertEquals(1, a.diagnostics().reorderedFrames)

        // 回退不推进序号：51 相对 50 仍然是连续的
        val next = a.feed(audioMessage(51, opus(0)))
        assertEquals(0, next[0].missingBefore)
    }

    @Test
    fun `重复帧被计数且不算空洞`() {
        val a = OpusStreamAssembler()
        a.feed(audioMessage(20, opus(0)))
        val frames = a.feed(audioMessage(20, opus(0)))

        assertEquals(1, frames.size)
        assertEquals(0, frames[0].missingBefore)
        assertEquals(1, a.diagnostics().duplicateFrames)
    }

    /**
     * Opus 负载里出现 0xAA 是常事。若把它当帧头读出一个荒唐的 length 并存进 carryOver，
     * 之后就再也对不齐了——必须跳过继续找。
     */
    @Test
    fun `数据里的假帧头不会让解析卡死`() {
        val a = OpusStreamAssembler()
        // 0xAA 后跟一个超大 length，随后才是真正的消息
        val junk = byteArrayOf(0xAA.toByte(), 0x10, 0xFF.toByte(), 0xFF.toByte(), 1, 0, 0)
        val frames = a.feed(junk + audioMessage(31, opus(4)))

        assertEquals(1, frames.size)
        assertEquals(31, frames[0].frameId)
        assertTrue("应记录为了重新对齐丢掉的字节", a.diagnostics().resyncSkippedBytes > 0)
    }

    /** 16 小时会话里永远凑不齐的帧不能无限堆在 map 里。 */
    @Test
    fun `未凑齐的帧超过上限后被淘汰并计数`() {
        val a = OpusStreamAssembler(maxPendingFrames = 4)
        repeat(10) { i ->
            a.feed(audioMessage(1000 + i, opus(0).copyOfRange(0, 40), chunkId = 0, totalChunks = 2))
        }
        assertEquals(6, a.diagnostics().incompleteFrames)
    }

    /**
     * 回归：断链后**必须**清掉序号水位。
     *
     * 我原来保留它，理由是"以便继续算空洞"。2026-08-08 八台设备实测证明那是错的：
     * 设备重连后从低序号重新开始，每一帧都低于旧水位而被判成乱序，且判乱序时
     * 不推进水位，于是后续每帧都重复触发——一台设备累计出 2384 次假"乱序"。
     *
     * 跨越一次断连本来就无从知道设备是继续计数还是从头开始，
     * 而按墙钟算的 unaccounted 已经覆盖了这段缺口。
     */
    @Test
    fun `断链后序号重新起算而不是死守旧水位`() {
        val a = OpusStreamAssembler()
        a.feed(audioMessage(60000, opus(0)))
        val msg = audioMessage(60001, opus(0))
        a.feed(msg.copyOfRange(0, 20))

        a.onLinkInterrupted()
        assertEquals(20, a.diagnostics().droppedCarryOverBytes)

        // 设备重连后从 0 重新计数：不该产生任何"乱序"，也不该报出巨大空洞
        var reorderedBurst = 0
        for (i in 0 until 50) {
            val f = a.feed(audioMessage(i, opus(0)))
            if (f.isNotEmpty()) reorderedBurst += f[0].missingBefore
        }
        assertEquals("重新起算之后不该报空洞", 0, reorderedBurst)
        assertEquals("更不该把它们记成乱序", 0, a.diagnostics().reorderedFrames)
    }

    /**
     * 设备在**同一个连接内**重置序号也要能识别。
     * 单独一两帧低于水位是真乱序；连续低于水位是重置。
     */
    @Test
    fun `连接内序号重置被识别而不是记成成片乱序`() {
        val a = OpusStreamAssembler()
        for (i in 5000 until 5010) a.feed(audioMessage(i, opus(0)))

        // 设备从 0 重新开始
        for (i in 0 until 100) a.feed(audioMessage(i, opus(0)))

        val d = a.diagnostics()
        assertEquals("应当识别为一次重置", 1, d.sequenceResets)
        assertTrue(
            "重置不该留下成片的假乱序，实际 ${d.reorderedFrames}",
            d.reorderedFrames <= 2
        )
    }

    /** 真正的单帧乱序仍然要被认出来，不能被重置检测吞掉。 */
    @Test
    fun `孤立的乱序帧仍记为乱序`() {
        val a = OpusStreamAssembler()
        a.feed(audioMessage(50, opus(0)))
        a.feed(audioMessage(48, opus(0)))   // 单独一帧回退
        a.feed(audioMessage(51, opus(0)))   // 立刻回到正常序列

        val d = a.diagnostics()
        assertEquals(1, d.reorderedFrames)
        assertEquals(0, d.sequenceResets)
    }

    @Test
    fun `一个 notification 里的多条消息全部解析`() {
        val a = OpusStreamAssembler()
        val batch = audioMessage(1, opus(1)) + audioMessage(2, opus(2)) + audioMessage(3, opus(3))
        val frames = a.feed(batch)

        assertEquals(3, frames.size)
        assertEquals(listOf(1, 2, 3), frames.map { it.frameId })
        assertTrue(frames.all { it.missingBefore == 0 })
    }
}
