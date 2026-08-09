package com.example.nunarecorder.data

import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionManifestIO
import com.example.nunarecorder.sync.SessionSyncStatus
import com.example.nunarecorder.sync.SessionSyncStatusIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 2026-08-09：35 条会话时录音列表就卡住，明天要装 700–900 条。
 * 缓存是这个修复的核心，所以它必须有测试——尤其"删掉的会话不能靠缓存复活"，
 * 那是缓存最容易出的错，而且错了会让用户以为数据还在。
 */
class RecordingEntryLoaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun makeSession(name: String, startedAtMs: Long, segments: Int): File {
        val dir = tmp.newFolder(name)
        val m = SessionManifest(
            sessionId = name,
            deviceName = "dev",
            deviceAddress = "AA:BB",
            startedAtMs = startedAtMs
        )
        repeat(segments) { i ->
            m.segments.add(
                AudioSegmentEntryFactory.make(i, startedAtMs)
            )
        }
        SessionManifestIO.write(dir, m)
        return dir
    }

    private object AudioSegmentEntryFactory {
        fun make(i: Int, base: Long) = com.example.nunarecorder.session.AudioSegmentEntry(
            index = i,
            file = "audio/seg_%04d.opus".format(i),
            startMs = i * 60_000L,
            endMs = (i + 1) * 60_000L,
            bytes = 240_000L,
            durationMs = 60_000L
        )
    }

    @Test
    fun `第二次加载全部命中缓存，不再解析 manifest`() {
        val dirs = (1..5).map { makeSession("s$it", 1_700_000_000_000L + it * 1000, 60) }
        val loader = RecordingEntryLoader(sessionDirs = { dirs }, legacyFiles = { emptyList() })

        assertEquals(5, loader.load().size)
        assertEquals(5, loader.misses)
        assertEquals(0, loader.hits)

        assertEquals(5, loader.load().size)
        assertEquals("第二次不该再解析任何 manifest", 5, loader.misses)
        assertEquals(5, loader.hits)
    }

    @Test
    fun `manifest 变了就重新解析，不会拿旧内容糊弄`() {
        val dir = makeSession("live", 1_700_000_000_000L, 1)
        val loader = RecordingEntryLoader(sessionDirs = { listOf(dir) }, legacyFiles = { emptyList() })
        assertEquals(1, (loader.load()[0] as RecordingEntry.Session).totalSegments)

        // 模拟录制中又写了一段：内容变长，缓存键随之改变
        val m = SessionManifest.load(com.example.nunarecorder.session.SessionPaths.manifestFile(dir))!!
        m.segments.add(AudioSegmentEntryFactory.make(1, 0))
        SessionManifestIO.write(dir, m)

        val reloaded = loader.load()[0] as RecordingEntry.Session
        assertEquals("内容变了必须重新解析", 2, reloaded.totalSegments)
        assertEquals(2, loader.misses)
    }

    @Test
    fun `会话被删掉之后不能靠缓存复活`() {
        val a = makeSession("a", 1_700_000_002_000L, 2)
        val b = makeSession("b", 1_700_000_001_000L, 2)
        var visible = listOf(a, b)
        val loader = RecordingEntryLoader(sessionDirs = { visible }, legacyFiles = { emptyList() })
        assertEquals(2, loader.load().size)

        // 清理已同步：目录没了
        b.deleteRecursively()
        visible = listOf(a)

        val after = loader.load()
        assertEquals(1, after.size)
        assertEquals("a", (after[0] as RecordingEntry.Session).displayName)
    }

    @Test
    fun `按开始时间倒序，最新的在最前面`() {
        val old = makeSession("old", 1_700_000_000_000L, 1)
        val new = makeSession("new", 1_700_000_900_000L, 1)
        val loader = RecordingEntryLoader(
            sessionDirs = { listOf(old, new) }, legacyFiles = { emptyList() }
        )
        assertEquals("new", loader.load()[0].displayName)
    }

    @Test
    fun `坏掉的 manifest 只跳过那一条，不让整个列表打不开`() {
        val good = makeSession("good", 1_700_000_000_000L, 1)
        val broken = tmp.newFolder("broken")
        File(broken, "manifest.json").writeText("{ 这不是 JSON")

        val loader = RecordingEntryLoader(
            sessionDirs = { listOf(good, broken) }, legacyFiles = { emptyList() }
        )
        val list = loader.load()
        assertEquals(1, list.size)
        assertEquals("good", list[0].displayName)
    }

    @Test
    fun `上传完成只改 sync_status，列表也必须跟着变`() {
        // 用户 2026-08-09 实测："全部上传完，全部上传还是显示为 10，
        // 同时清理已同步也是 10……切换界面刷新后才显示正常"。
        // 成因是缓存键只看 manifest，而上传**不动 manifest**，只写 sync_status.json。
        val dir = makeSession("uploaded", 1_700_000_000_000L, 1)
        val loader = RecordingEntryLoader(sessionDirs = { listOf(dir) }, legacyFiles = { emptyList() })
        assertEquals(null, (loader.load()[0] as RecordingEntry.Session).syncStatus)

        SessionSyncStatusIO.write(
            dir,
            SessionSyncStatus(sessionId = "uploaded", status = "synced")
        )

        val after = loader.load()[0] as RecordingEntry.Session
        assertEquals("synced", after.syncStatus?.status)
        assertEquals("sync_status 变了必须重新构造条目", 2, loader.misses)
    }

    @Test
    fun `九百条会话第二次加载靠缓存，不做任何解析`() {
        // 明天的量级。第一次必然要解析，但之后每秒一次的刷新必须是纯内存。
        val dirs = (1..900).map { makeSession("s$it", 1_700_000_000_000L + it * 1000L, 1) }
        val loader = RecordingEntryLoader(sessionDirs = { dirs }, legacyFiles = { emptyList() })
        loader.load()
        assertEquals(900, loader.misses)

        val before = loader.misses
        repeat(3) { loader.load() }
        assertEquals("后续刷新不该再解析任何 manifest", before, loader.misses)
        assertTrue(loader.hits >= 2700)
    }
}
