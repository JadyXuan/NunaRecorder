package com.example.nunarecorder.sync

import com.example.nunarecorder.session.AudioSegmentEntry
import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 上传清单决定了「哪些文件真的会被送走」。
 *
 * 这里锁住的是**漏传**：手机上的会话迟早会被"清理已同步"删掉，
 * 一个没进清单的文件等于永久丢失，而界面全程显示"已同步"。
 * 这和 legacy 回退是同一类问题（`AGENTS.md` §3）——不是没实现，是安静地把数据落下。
 */
class SessionSyncInventoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File

    private fun setUpSession(): SessionManifest {
        dir = tmp.newFolder("nuna_AA_1800000000000")
        fun write(rel: String, text: String) {
            File(dir, rel).also { it.parentFile?.mkdirs() }.writeText(text)
        }
        write(SessionPaths.MANIFEST_FILE, "{}")
        write(SessionPaths.CONTEXT_FILE, """{"type":"imu"}""")
        write(SessionPaths.MMWAVE_FILE, """{"type":"mmwave_raw"}""")
        write(SessionPaths.MMWAVE_STATE_FILE, """{"type":"mmwave_state"}""")
        write(SessionPaths.VAD_PRELABEL_FILE, "{}")
        write("audio/seg_000.opus", "x")
        return SessionManifest(
            sessionId = dir.name,
            deviceName = "dev",
            deviceAddress = null,
            startedAtMs = 1_800_000_000_000L,
            segments = mutableListOf(
                AudioSegmentEntry(0, "audio/seg_000.opus", 0L, 60_000L, 1L, 60_000L)
            )
        )
    }

    private fun paths(
        includeContext: Boolean = true,
        includeVad: Boolean = true,
        includeMmWave: Boolean = true
    ): List<String> {
        // setUpSession() 会赋值 dir，必须先跑完再取——写成参数会先求值 dir
        val manifest = setUpSession()
        return SessionSyncInventory
            .buildRelative(dir, manifest, includeContext, includeVad, includeMmWave)
            .map { it.path }
    }

    @Test
    fun `默认把毫米波的两个文件都带上`() {
        val p = paths()
        assertTrue(SessionPaths.MMWAVE_FILE in p)
        assertTrue("只有数据没有开关时间线，事后对齐会把雷达开关误判成掉线",
            SessionPaths.MMWAVE_STATE_FILE in p)
    }

    @Test
    fun `取消毫米波时两个文件一起不传`() {
        val p = paths(includeMmWave = false)
        assertFalse(SessionPaths.MMWAVE_FILE in p)
        assertFalse(SessionPaths.MMWAVE_STATE_FILE in p)
        assertTrue("上下文不受影响", SessionPaths.CONTEXT_FILE in p)
    }

    @Test
    fun `毫米波不再被上下文开关连坐`() {
        // 2026-08-14 之前它挂在 includeContext 下面：参与者只想不传 GPS/IMU，
        // 结果毫米波也一起没了，而界面上根本看不出来。
        val p = paths(includeContext = false)
        assertFalse(SessionPaths.CONTEXT_FILE in p)
        assertTrue(SessionPaths.MMWAVE_FILE in p)
    }

    @Test
    fun `音频和 manifest 永远在清单里`() {
        val p = paths(includeContext = false, includeVad = false, includeMmWave = false)
        assertTrue(SessionPaths.MANIFEST_FILE in p)
        assertTrue("audio/seg_000.opus" in p)
    }

    @Test
    fun `没有毫米波的旧会话不会因为勾选而出现空条目`() {
        // 旧会话根本没有这两个文件。清单里混进不存在的路径，
        // 服务端 commit 会一直报 missing，会话永远停在 partial。
        val bare = tmp.newFolder("bare")
        File(bare, SessionPaths.MANIFEST_FILE).also { it.parentFile?.mkdirs() }.writeText("{}")
        val list = SessionSyncInventory.buildRelative(
            bare,
            SessionManifest(
                sessionId = "bare", deviceName = "dev", deviceAddress = null, startedAtMs = 1L
            ),
            includeContext = true,
            includeVad = true,
            includeMmWave = true
        )
        assertEquals(listOf(SessionPaths.MANIFEST_FILE), list.map { it.path })
    }
}
