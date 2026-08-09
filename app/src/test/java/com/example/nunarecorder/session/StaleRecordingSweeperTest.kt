package com.example.nunarecorder.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 用户 2026-08-09：「有一个被漏掉的，是我最后上传的一个文件，这个文件必须手动上传，
 * 否则不被算到全部上传里面。」
 *
 * 进程被杀掉时 `recording.active` 不会被写回 false，那个会话就永远进不了批量上传。
 * 采集一整天有十几个会话，中途被杀一次就漏一个，**而漏掉的那个没有任何提示**。
 */
class StaleRecordingSweeperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun session(
        name: String,
        active: Boolean,
        ended: Long? = null,
        segments: Int = 0,
        startedAtMs: Long = 1_700_000_000_000L
    ): File {
        val dir = tmp.newFolder(name)
        val m = SessionManifest(
            sessionId = name,
            deviceName = "dev",
            deviceAddress = null,
            startedAtMs = startedAtMs,
            endedAtMs = ended
        )
        repeat(segments) { i ->
            m.segments.add(
                AudioSegmentEntry(
                    index = i, file = "audio/s$i.opus",
                    startMs = i * 60_000L, endMs = (i + 1) * 60_000L,
                    bytes = 240_000L, durationMs = 60_000L
                )
            )
        }
        m.recordingActive = active
        if (active) { m.openSegmentIndex = segments; m.openSegmentBytes = 1234L }
        SessionManifestIO.write(dir, m)
        return dir
    }

    private fun reload(dir: File) = SessionManifest.load(SessionPaths.manifestFile(dir))!!

    @Test
    fun `被杀掉留下的 recordingActive 会被清掉`() {
        val dir = session("killed", active = true, segments = 2)
        val r = StaleRecordingSweeper.sweep(listOf(dir), activePath = null)

        assertEquals(listOf("killed"), r.cleared)
        val m = reload(dir)
        assertFalse(m.recordingActive)
        assertEquals(null, m.openSegmentIndex)
        assertEquals(0L, m.openSegmentBytes)
    }

    @Test
    fun `此刻真正在录的那个不能碰`() {
        val live = session("live", active = true)
        val r = StaleRecordingSweeper.sweep(listOf(live), activePath = live.absolutePath)

        assertTrue("正在录的必须原样保留", r.cleared.isEmpty())
        assertTrue(reload(live).recordingActive)
    }

    @Test
    fun `已经正常收尾的不重复改写`() {
        val done = session("done", active = false, ended = 1_700_000_100_000L)
        val r = StaleRecordingSweeper.sweep(listOf(done), activePath = null)
        assertTrue(r.cleared.isEmpty())
        assertEquals(1_700_000_100_000L, reload(done).endedAtMs)
    }

    @Test
    fun `补的结束时间用最后一段的结尾，不能用现在`() {
        // 用"现在"会把一次凌晨的崩溃写成一段长达数小时的采集，
        // 而 ended_at_ms 会进服务端，是会被当成事实读的。
        val start = 1_700_000_000_000L
        val dir = session("crashed", active = true, segments = 3, startedAtMs = start)
        StaleRecordingSweeper.sweep(listOf(dir), activePath = null)
        assertEquals(start + 180_000L, reload(dir).endedAtMs)
    }

    @Test
    fun `一段都没有的空会话退回开始时间`() {
        val start = 1_700_000_000_000L
        val dir = session("empty", active = true, segments = 0, startedAtMs = start)
        StaleRecordingSweeper.sweep(listOf(dir), activePath = null)
        assertEquals(start, reload(dir).endedAtMs)
    }

    @Test
    fun `一个坏 manifest 不影响其余会话被修复`() {
        val broken = tmp.newFolder("broken")
        File(broken, "manifest.json").writeText("{ 坏的")
        val ok = session("ok", active = true)
        val r = StaleRecordingSweeper.sweep(listOf(broken, ok), activePath = null)
        assertEquals(listOf("ok"), r.cleared)
    }
}
