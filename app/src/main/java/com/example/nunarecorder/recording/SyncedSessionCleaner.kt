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

    /** 列出可以安全删除的会话，不做任何改动。 */
    fun listDeletable(): List<Candidate> =
        SessionPaths.listSessionDirs().mapNotNull { dir ->
            val status = SessionSyncStatus.load(dir) ?: return@mapNotNull null
            if (status.status != "synced") return@mapNotNull null
            // 再核一遍每个文件都确认过：整体 synced 但个别文件 failed 的话不能删
            if (status.files.any { it.status != "synced" }) return@mapNotNull null
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
