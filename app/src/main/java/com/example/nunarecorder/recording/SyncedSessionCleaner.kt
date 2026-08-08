package com.example.nunarecorder.recording

import com.example.nunarecorder.session.SessionPaths
import com.example.nunarecorder.sync.SessionSyncStatus
import java.io.File

/**
 * 删除**已确认同步**的会话，腾出手机空间。
 *
 * 判据只有一个：`labels/sync_status.json` 的 `status == "synced"`，
 * 那是服务端 commit 返回 `synced` 之后才写的。`partial` / `failed` / 没有这个文件
 * 一律不删——手机上的副本可能是唯一的一份。
 */
object SyncedSessionCleaner {

    data class Candidate(val dir: File, val displayName: String, val bytes: Long)

    data class Result(val deleted: Int, val freedBytes: Long, val failed: List<String>)

    /** 判据：整体 synced，且每个文件都单独确认过（整体 synced 但个别文件 failed 不能删） */
    private fun isDeletable(dir: File): Boolean {
        val status = SessionSyncStatus.load(dir) ?: return false
        return status.status == "synced" && status.files.all { it.status == "synced" }
    }

    /**
     * 只数个数，**不遍历文件求体积**。
     *
     * 界面上那行"可清理 N"每次列表变化都要刷新，而求体积要 walk 每个会话的全部文件：
     * 35 个会话 × 60 段就是两千多次 stat，一天 900 段更多。体积只有确认对话框需要。
     */
    fun countDeletable(): Int = SessionPaths.listSessionDirs().count { isDeletable(it) }

    /** 列出可以安全删除的会话（含体积，会遍历文件），不做任何改动。 */
    fun listDeletable(): List<Candidate> =
        SessionPaths.listSessionDirs()
            .filter { isDeletable(it) }
            .map { dir ->
                Candidate(dir, dir.name, dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() })
            }

    fun deleteAll(candidates: List<Candidate>): Result {
        var deleted = 0
        var freed = 0L
        val failed = mutableListOf<String>()
        for (c in candidates) {
            // 删之前再确认一次状态：列表可能是几分钟前算的
            val status = SessionSyncStatus.load(c.dir)
            if (status?.status != "synced") {
                failed.add("${c.displayName}（状态已变，跳过）")
                continue
            }
            if (c.dir.deleteRecursively()) {
                deleted++
                freed += c.bytes
            } else {
                failed.add(c.displayName)
            }
        }
        return Result(deleted, freed, failed)
    }
}
