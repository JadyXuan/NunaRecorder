package com.example.nunarecorder.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-07-31 实测：客户端每次点上传都新生成 `client_upload_id`，
 * 服务端认不出是同一次上传，所有文件重传一遍。协议本来就支持续传。
 */
class SessionSyncPlannerTest {

    private fun entry(path: String, sha: String, status: String = "pending") = SyncFileEntry(
        path = path,
        sha256 = sha,
        size = 1000,
        mediaType = "audio/opus",
        status = status
    )

    private val inventory = listOf(
        entry("manifest.json", "aa"),
        entry("audio/seg_000.opus", "bb"),
        entry("audio/seg_001.opus", "cc")
    )

    private fun previous(
        sessionId: String = "s1",
        clientUploadId: String? = "prev-uuid",
        files: List<SyncFileEntry> = inventory
    ) = SessionSyncStatus(
        sessionId = sessionId,
        status = "partial",
        clientUploadId = clientUploadId,
        files = files.toMutableList()
    )

    @Test
    fun `没有历史记录时生成新的 client_upload_id`() {
        val plan = SessionSyncPlanner.plan(null, "s1", inventory) { "new-uuid" }

        assertEquals("new-uuid", plan.clientUploadId)
        assertFalse(plan.resumingPreviousUpload)
        assertTrue(plan.files.all { it.status == "pending" })
    }

    @Test
    fun `清单不变时复用上一次的 client_upload_id`() {
        val plan = SessionSyncPlanner.plan(previous(), "s1", inventory) { "new-uuid" }

        assertEquals("prev-uuid", plan.clientUploadId)
        assertTrue(plan.resumingPreviousUpload)
        assertNull(plan.restartReason)
    }

    @Test
    fun `已确认同步过的文件不再标成待传`() {
        val prev = previous(
            files = listOf(
                entry("manifest.json", "aa", status = "synced"),
                entry("audio/seg_000.opus", "bb", status = "synced"),
                entry("audio/seg_001.opus", "cc", status = "failed")
            )
        )
        val plan = SessionSyncPlanner.plan(prev, "s1", inventory) { "new-uuid" }

        assertEquals(
            listOf("synced", "synced", "pending"),
            plan.files.map { it.status }
        )
    }

    /**
     * 关键安全条件：服务端 `init` 命中已有 `client_upload_id` 时会直接返回旧 upload，
     * **不会**用新清单去登记文件。上次上传失败后又录了新分段还复用旧 id，
     * 新分段永远不会被登记，commit 却按旧清单返回 synced，会话被标成已同步——数据就没了。
     */
    @Test
    fun `会话在上次上传后又录了新内容时必须重开一次上传`() {
        val grown = inventory + entry("audio/seg_002.opus", "dd")
        val plan = SessionSyncPlanner.plan(previous(), "s1", grown) { "new-uuid" }

        assertEquals("new-uuid", plan.clientUploadId)
        assertFalse(plan.resumingPreviousUpload)
        assertNotNull("必须说明为什么没复用", plan.restartReason)
        assertTrue(plan.files.all { it.status == "pending" })
    }

    @Test
    fun `文件内容变了也要重开一次上传`() {
        val changed = listOf(
            entry("manifest.json", "aa"),
            entry("audio/seg_000.opus", "bb"),
            entry("audio/seg_001.opus", "CHANGED")
        )
        val plan = SessionSyncPlanner.plan(previous(), "s1", changed) { "new-uuid" }

        assertFalse(plan.resumingPreviousUpload)
        assertEquals("new-uuid", plan.clientUploadId)
    }

    @Test
    fun `历史记录属于别的会话时不复用`() {
        val plan = SessionSyncPlanner.plan(previous(sessionId = "other"), "s1", inventory) { "new-uuid" }
        assertFalse(plan.resumingPreviousUpload)
    }

    @Test
    fun `历史记录里没有 client_upload_id 时不复用`() {
        val plan = SessionSyncPlanner.plan(previous(clientUploadId = null), "s1", inventory) { "new-uuid" }
        assertFalse(plan.resumingPreviousUpload)
        assertEquals("new-uuid", plan.clientUploadId)
    }

    // ── 服务端状态校正 ────────────────────────────────────────────────────

    @Test
    fun `服务端已收到的文件被跳过`() {
        val files = inventory.map { it.copy() }.toMutableList()
        val skipped = SessionSyncPlanner.applyServerState(
            files,
            listOf(
                ServerFileState("manifest.json", "received", "aa"),
                ServerFileState("audio/seg_000.opus", "received", "bb"),
                ServerFileState("audio/seg_001.opus", "pending", "cc")
            )
        )

        assertEquals(2, skipped)
        assertEquals(listOf("synced", "synced", "pending"), files.map { it.status })
    }

    /** 服务端那份内容和本机对不上时必须重传，否则会留下一个校验不过的会话。 */
    @Test
    fun `服务端文件哈希不一致时仍然重传`() {
        val files = inventory.map { it.copy() }.toMutableList()
        val skipped = SessionSyncPlanner.applyServerState(
            files,
            listOf(ServerFileState("manifest.json", "received", "DIFFERENT"))
        )

        assertEquals(0, skipped)
        assertTrue(files.all { it.status == "pending" })
    }

    /** 本机以为传完了、服务端其实没有：以服务端为准。 */
    @Test
    fun `服务端状态覆盖本机的乐观记录`() {
        val files = inventory.map { it.copy(status = "synced") }.toMutableList()
        val skipped = SessionSyncPlanner.applyServerState(files, emptyList())

        assertEquals(0, skipped)
        assertTrue(files.all { it.status == "pending" })
    }
}
