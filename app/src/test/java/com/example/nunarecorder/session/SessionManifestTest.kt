package com.example.nunarecorder.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * manifest 是服务端入库和研究者对齐时间轴的唯一依据，新增字段必须能原样读回来。
 * 2026-07-31 服务端把相对偏移 `start_ms` 当成 epoch 用，整批数据落到 1970-01-01——
 * 这里的往返测试就是防止再出现「两边对同一个字段理解不一致」。
 */
class SessionManifestTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun sample() = SessionManifest(
        sessionId = "nuna_4C_FF_01_A0_07_3C_1800000000000",
        deviceName = "nuna_device_073C",
        deviceAddress = "4C:FF:01:A0:07:3C",
        startedAtMs = 1_800_000_000_000L,
        endedAtMs = 1_800_000_180_000L,
        segments = mutableListOf(
            AudioSegmentEntry(
                index = 0,
                file = "audio/seg_000.opus",
                startMs = 0L,
                endMs = 59_040L,
                bytes = 236_160L,
                durationMs = 59_040L,
                frames = SegmentFrameStats(
                    expectedPackets = 3000,
                    receivedPackets = 2952,
                    deviceFrames = 82,
                    deviceSequenceLost = 2,
                    gaps = listOf(FrameGap(atFrameOffset = 40, missingFrames = 2, nextFrameId = 6418))
                )
            ),
            AudioSegmentEntry(
                index = 2,
                file = "audio/seg_002.opus",
                startMs = 120_000L,
                endMs = 179_000L,
                bytes = 236_000L,
                durationMs = 59_000L
            )
        ),
        link = LinkHealth(
            events = listOf(
                LinkGapEntry(1_800_000_060_000L, 1_800_000_115_000L, "connection_timeout(8)", 3),
                LinkGapEntry(1_800_000_170_000L, null, "bluetooth_off", 1)
            ),
            resyncSkippedBytes = 137,
            incompleteFrames = 2,
            duplicateFrames = 1,
            reorderedFrames = 4,
            droppedCarryOverBytes = 60
        ),
        missingSegments = listOf(1)
    )

    private fun roundTrip(m: SessionManifest): SessionManifest {
        val dir = temp.newFolder(m.sessionId)
        SessionManifestIO.write(dir, m)
        return SessionManifest.load(SessionPaths.manifestFile(dir))!!
    }

    @Test
    fun `帧账目往返不丢字段`() {
        val loaded = roundTrip(sample())
        val frames = loaded.segments.first().frames
        assertNotNull(frames)
        assertEquals(3000, frames!!.expectedPackets)
        assertEquals(2952, frames.receivedPackets)
        assertEquals(48, frames.missingPackets)
        assertEquals("设备帧和 20ms 包是两个单位，往返后不能串", 82, frames.deviceFrames)
        assertEquals(2, frames.deviceSequenceLost)
        assertEquals(0.984f, frames.completeness, 0.001f)

        val gap = frames.gaps.single()
        assertEquals(40, gap.atFrameOffset)
        assertEquals(2, gap.missingFrames)
        assertEquals(6418, gap.nextFrameId)
    }

    @Test
    fun `没有帧账目的旧分段读成 null 而不是零`() {
        val loaded = roundTrip(sample())
        assertNull(loaded.segments[1].frames)
    }

    @Test
    fun `断连记录往返保留绝对时间和未恢复状态`() {
        val loaded = roundTrip(sample())
        assertEquals(2, loaded.link.disconnectCount)

        val first = loaded.link.events[0]
        assertEquals(1_800_000_060_000L, first.startAtMs)
        assertEquals(1_800_000_115_000L, first.endAtMs)
        assertEquals("connection_timeout(8)", first.reason)
        assertEquals(3, first.reconnectAttempts)
        assertEquals(55_000L, first.downMs)

        val second = loaded.link.events[1]
        assertNull("未恢复的中断必须保持 null，不能变成 0", second.endAtMs)
        assertNull(second.downMs)

        assertEquals("只统计已结束的中断时长", 55_000L, loaded.link.totalDownMs)
    }

    @Test
    fun `重组器诊断往返`() {
        val a = roundTrip(sample()).link
        assertEquals(137, a.resyncSkippedBytes)
        assertEquals(2, a.incompleteFrames)
        assertEquals(1, a.duplicateFrames)
        assertEquals(4, a.reorderedFrames)
        assertEquals(60, a.droppedCarryOverBytes)
    }

    @Test
    fun `分段索引空洞被显式记录`() {
        val loaded = roundTrip(sample())
        assertEquals(listOf(0, 2), loaded.segments.map { it.index })
        assertEquals(listOf(1), loaded.missingSegments)
    }

    /** 旧 manifest（没有 link / frames / missing_segments）必须还能读。 */
    @Test
    fun `旧格式 manifest 仍可读取`() {
        val dir = temp.newFolder("legacy")
        File(dir, "manifest.json").writeText(
            """
            {
              "format_version": 1,
              "session_id": "nuna_device_1",
              "device_name": "nuna_device",
              "device_address": "AA:BB:CC:DD:EE:FF",
              "started_at_ms": 1700000000000,
              "segment_duration_ms": 60000,
              "audio": {
                "codec": "opus_raw",
                "segments": [
                  {"index":0,"file":"audio/seg_000.opus","start_ms":0,"end_ms":60000,
                   "bytes":240000,"duration_ms":60000}
                ]
              }
            }
            """.trimIndent()
        )
        val loaded = SessionManifest.load(File(dir, "manifest.json"))
        assertNotNull(loaded)
        assertEquals(1, loaded!!.segments.size)
        assertNull(loaded.segments[0].frames)
        assertEquals(0, loaded.link.disconnectCount)
        assertTrue(loaded.missingSegments.isEmpty())
    }

    /**
     * `start_ms` 是**相对会话开始的偏移**。服务端必须做 `started_at_ms + start_ms`。
     * 把这条语义钉在测试里，免得 fixture 再一次编码错误的假设。
     */
    @Test
    fun `分段起点是相对偏移不是 epoch`() {
        val loaded = roundTrip(sample())
        val seg = loaded.segments.first()
        assertEquals(0L, seg.startMs)
        assertEquals(1_800_000_000_000L, loaded.startedAtMs + seg.startMs)
        assertTrue("绝对时间必须晚于 2020 年", loaded.startedAtMs + seg.startMs > 1_577_836_800_000L)
    }
}
