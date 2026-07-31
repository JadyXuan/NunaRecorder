package com.example.nunarecorder.recording

import com.example.nunarecorder.ble.OpusStreamAssembler
import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 分段轮转、断连记录、帧账目。
 *
 * 时钟是注入的，所以「录 3 分钟然后断连 90 秒」在毫秒内跑完，不用真等。
 */
class SessionRecorderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var now = 1_800_000_000_000L
    private val logs = mutableListOf<String>()

    private fun recorder(closed: MutableList<SessionRecorder.ClosedSegment> = mutableListOf()) =
        SessionRecorder(
            onLog = { logs.add(it) },
            clock = { now },
            onSegmentClosed = { closed.add(it) }
        )

    private fun options(durationMs: Long = 60_000L) = RecordingOptions(
        segmentEnabled = true,
        segmentDurationMs = durationMs,
        autoVadOnRecord = false
    )

    private fun opus(seed: Int) = ByteArray(OpusStreamAssembler.OPUS_FRAME_SIZE) {
        ((seed + it) and 0xFF).toByte()
    }

    private fun audioMessage(frameId: Int, opusChunk: ByteArray): ByteArray {
        val payload = ByteArray(14 + opusChunk.size)
        payload[0] = (frameId and 0xFF).toByte()
        payload[1] = ((frameId shr 8) and 0xFF).toByte()
        payload[4] = 0
        payload[5] = 1
        opusChunk.copyInto(payload, 14)
        val msg = ByteArray(7 + payload.size)
        msg[0] = 0xAA.toByte()
        msg[1] = 0x10
        msg[2] = (payload.size and 0xFF).toByte()
        msg[3] = ((payload.size shr 8) and 0xFF).toByte()
        msg[4] = 1
        payload.copyInto(msg, 7)
        return msg
    }

    /** 喂 n 帧，序号连续；时间不动，由调用方控制墙钟 */
    private fun feedFrames(r: SessionRecorder, startFrameId: Int, count: Int) {
        repeat(count) { r.feed(audioMessage(startFrameId + it, opus(it))) }
    }

    private fun manifestOf(dir: File): SessionManifest =
        SessionManifest.load(SessionPaths.manifestFile(dir))!!

    private fun newSession(): File = temp.newFolder("nuna_TEST_$now")

    // ── 分段轮转 ──────────────────────────────────────────────────────────

    @Test
    fun `墙钟推进时分段按 60 秒轮转`() {
        val dir = newSession()
        val r = recorder()
        r.start(dir, "dev", "AA:BB", options())

        feedFrames(r, 0, 10)
        now += 60_000
        r.tick()
        feedFrames(r, 10, 10)
        now += 60_000
        r.tick()
        r.stop()

        val m = manifestOf(dir)
        assertEquals(listOf(0, 1), m.segments.map { it.index })
        assertEquals(0L, m.segments[0].startMs)
        assertEquals(60_000L, m.segments[1].startMs)
    }

    /**
     * 回归：BLE 断了以后 `feed()` 不再被调用，旧实现的轮转就此停摆——
     * 断连后的整段时间既没有音频也没有任何痕迹。
     */
    @Test
    fun `没有音频时 tick 仍然推进分段轮转`() {
        val dir = newSession()
        val r = recorder()
        r.start(dir, "dev", "AA:BB", options())
        feedFrames(r, 0, 5)

        // 3 分半一帧不来，只有 tick
        repeat(3) {
            now += 60_000
            r.tick()
        }
        now += 30_000
        r.stop()

        val m = manifestOf(dir)
        assertEquals("只有第 0 段有音频", listOf(0), m.segments.map { it.index })
        assertEquals("第 1、2、3 段应被显式记成缺失", listOf(1, 2, 3), m.missingSegments)
    }

    /**
     * 回归：旧的 `openNextSegment()` 用 `manifest.segments.size` 当索引。
     * 空段不进 manifest，索引就永远追不上 `expectedIndex`，
     * `while (currentSegmentIndex < expectedIndex)` 变成死循环。
     * 这个用例在旧实现上会挂死，不是断言失败。
     */
    @Test(timeout = 5_000)
    fun `空段之后索引仍按墙钟前进而不是卡住`() {
        val dir = newSession()
        val r = recorder()
        r.start(dir, "dev", "AA:BB", options())

        // 第 0 段空
        now += 60_000
        r.tick()
        // 第 1 段有音频
        feedFrames(r, 0, 5)
        now += 60_000
        r.tick()
        r.stop()

        val m = manifestOf(dir)
        assertEquals(listOf(1), m.segments.map { it.index })
        assertEquals("有音频那段的起点必须对齐第 1 格墙钟", 60_000L, m.segments[0].startMs)
        assertEquals(listOf(0), m.missingSegments)
    }

    @Test
    fun `一次 tick 跨过多格时补齐所有中间段`() {
        val dir = newSession()
        val r = recorder()
        r.start(dir, "dev", "AA:BB", options())
        feedFrames(r, 0, 5)

        // 熄屏 5 分钟没有任何调用，回来只 tick 一次
        now += 5 * 60_000
        r.tick()
        r.stop()

        val m = manifestOf(dir)
        assertEquals(listOf(0), m.segments.map { it.index })
        assertEquals(listOf(1, 2, 3, 4), m.missingSegments)
    }

    // ── 帧账目 ────────────────────────────────────────────────────────────

    @Test
    fun `每段写入期望帧数实到帧数和空洞位置`() {
        val dir = newSession()
        val r = recorder()
        r.start(dir, "dev", "AA:BB", options())

        feedFrames(r, 0, 10)
        // 10..14 丢失，从 15 继续
        feedFrames(r, 15, 5)
        now += 60_000
        r.tick()
        r.stop()

        val frames = manifestOf(dir).segments.first().frames
        assertNotNull(frames)
        assertEquals(3000, frames!!.expectedFrames)
        assertEquals(15, frames.receivedFrames)
        assertEquals(5, frames.sequenceLostFrames)
        assertEquals(3000 - 15 - 5, frames.unaccountedFrames)

        assertEquals(1, frames.gaps.size)
        assertEquals("空洞出现在段内第 10 帧之后", 10, frames.gaps[0].atFrameOffset)
        assertEquals(5, frames.gaps[0].missingFrames)
        assertEquals(15, frames.gaps[0].nextFrameId)
    }

    /** 时长必须如实反映收到的帧，不能为了好看补零到 60 秒。 */
    @Test
    fun `分段时长按实到帧数算不做补偿`() {
        val dir = newSession()
        val r = recorder()
        r.start(dir, "dev", "AA:BB", options())

        feedFrames(r, 0, 100) // 100 × 20ms = 2000ms
        now += 60_000
        r.tick()
        r.stop()

        val seg = manifestOf(dir).segments.first()
        assertEquals(2000L, seg.durationMs)
        assertEquals(100L * OpusStreamAssembler.OPUS_FRAME_SIZE, seg.bytes)
    }

    @Test
    fun `末段不满一格时期望帧数按实际经过时间算`() {
        val dir = newSession()
        val r = recorder()
        r.start(dir, "dev", "AA:BB", options())

        feedFrames(r, 0, 500) // 10 秒音频
        now += 10_000
        r.stop()

        val frames = manifestOf(dir).segments.first().frames!!
        assertEquals("10 秒 = 500 帧，而不是整段的 3000", 500, frames.expectedFrames)
        assertEquals(500, frames.receivedFrames)
        assertEquals(0, frames.unaccountedFrames)
    }

    /** 跨段的半条消息不该被丢：assembler 是会话级的，不随分段重建。 */
    @Test
    fun `分段边界上被切开的消息仍能拼回来`() {
        val dir = newSession()
        val r = recorder()
        r.start(dir, "dev", "AA:BB", options())

        val msg = audioMessage(0, opus(1))
        r.feed(msg.copyOfRange(0, 30))
        now += 60_000
        r.tick() // 轮转发生在半条消息中间
        r.feed(msg.copyOfRange(30, msg.size))
        now += 60_000
        r.tick()
        r.stop()

        val m = manifestOf(dir)
        val seg1 = m.segments.first { it.index == 1 }
        assertEquals(
            "被切开的那一帧应落在第 1 段",
            OpusStreamAssembler.OPUS_FRAME_SIZE.toLong(),
            seg1.bytes
        )
    }

    // ── 断连记录 ──────────────────────────────────────────────────────────

    @Test
    fun `断连区间写进 manifest 且会话不被切断`() {
        val dir = newSession()
        val r = recorder()
        r.start(dir, "dev", "AA:BB", options())
        feedFrames(r, 0, 5)

        val gapStart = now
        r.onLinkLost("gatt_disconnected(status=8)")
        assertTrue(r.isLinkDown)
        assertTrue("断连不该结束会话", r.isRecording)

        r.onReconnectAttempt()
        r.onReconnectAttempt()
        now += 90_000
        r.tick()
        r.onLinkRestored()
        assertFalse(r.isLinkDown)

        feedFrames(r, 100, 5)
        now += 60_000
        r.stop()

        val m = manifestOf(dir)
        assertEquals("同一个会话，不是新建的", dir.name, m.sessionId)
        assertEquals(1, m.link.disconnectCount)
        val e = m.link.events.first()
        assertEquals(gapStart, e.startAtMs)
        assertEquals(gapStart + 90_000, e.endAtMs)
        assertEquals("gatt_disconnected(status=8)", e.reason)
        assertEquals(2, e.reconnectAttempts)
        assertEquals(90_000L, m.link.totalDownMs)
    }

    @Test
    fun `会话在断连状态下结束时结束时间留空`() {
        val dir = newSession()
        val r = recorder()
        r.start(dir, "dev", "AA:BB", options())
        feedFrames(r, 0, 5)
        r.onLinkLost("bluetooth_off")
        now += 30_000
        r.stop()

        val e = manifestOf(dir).link.events.single()
        assertEquals("bluetooth_off", e.reason)
        assertNull("没恢复过就不该假装恢复了", e.endAtMs)
    }

    @Test
    fun `断连期间的空洞不会被算成序号丢失`() {
        val dir = newSession()
        val r = recorder()
        r.start(dir, "dev", "AA:BB", options())
        feedFrames(r, 0, 5)

        r.onLinkLost("out_of_range")
        now += 120_000
        r.tick()
        r.onLinkRestored()
        // 设备重连后 frameId 从头开始（回退），不该被当成丢了 6 万帧
        feedFrames(r, 0, 5)
        now += 60_000
        r.stop()

        val m = manifestOf(dir)
        val lost = m.segments.sumOf { it.frames?.sequenceLostFrames ?: 0 }
        assertEquals(0, lost)
        assertEquals(1, m.link.disconnectCount)
    }

    @Test
    fun `录制中的实时统计反映丢帧和链路状态`() {
        val dir = newSession()
        val r = recorder()
        r.start(dir, "dev", "AA:BB", options())

        feedFrames(r, 0, 90)
        feedFrames(r, 100, 10) // 中间丢了 10 帧

        val stats = r.liveStats()!!
        assertEquals(100L, stats.receivedFrames)
        assertEquals(10L, stats.lostFrames)
        assertEquals(0.09f, stats.lossRatio, 0.001f)
        assertEquals(now, stats.lastFrameAtMs)
        assertFalse(stats.linkDown)

        r.onLinkLost("test")
        assertTrue(r.liveStats()!!.linkDown)
        r.stop()
    }

    @Test
    fun `封口回调带出分段文件供宿主入队 VAD`() {
        val dir = newSession()
        val closed = mutableListOf<SessionRecorder.ClosedSegment>()
        val r = recorder(closed)
        r.start(dir, "dev", "AA:BB", options())

        feedFrames(r, 0, 50)
        now += 60_000
        r.tick()

        assertEquals(1, closed.size)
        assertEquals(0, closed[0].entry.index)
        assertTrue(closed[0].file.exists())
        assertEquals("空段不该触发 VAD 入队", 1, closed.size)
        r.stop()
    }
}
