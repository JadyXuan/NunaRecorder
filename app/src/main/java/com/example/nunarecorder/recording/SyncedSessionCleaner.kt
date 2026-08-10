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

    /**
     * 此刻正在录的会话目录。清理和批量上传都必须绕开它。
     *
     * 在此之前"正在录的不会被删"只是**碰巧成立**：活动会话还没上传过，
     * 所以 `sync_status` 不是 `synced`，自然过不了判据。但那是一条依赖巧合的安全性——
     * 整点轮转、恢复重连、以后任何让"上传过的目录被继续写入"的改动，都会让它失效，
     * 而失效的表现是**边录边把正在写的会话删掉**。这是数据损坏路径，
     * 不能靠巧合，要显式拦。
     */
    private fun activeDir(): String? {
        // 只有链路确实还在会话中，stats 里的路径才算"正在录"。服务被杀时
        // stopRecording 走不到 publishStats(null)，残留的路径会一直排除掉那个会话。
        if (!RecordingController.link.value.isSessionActive) return null
        return RecordingController.stats.value?.sessionDir?.absolutePath
    }

    /** 判据：不是正在录的那个，整体 synced，且每个文件都单独确认过 */
    private fun isDeletable(dir: File, activePath: String? = activeDir()): Boolean {
        if (dir.absolutePath == activePath) return false
        val status = SessionSyncStatus.load(dir) ?: return false
        return status.status == "synced" && status.files.all { it.status == "synced" }
    }

    /**
     * 只数个数，**不遍历文件求体积**。
     *
     * 界面上那行"可清理 N"每次列表变化都要刷新，而求体积要 walk 每个会话的全部文件：
     * 35 个会话 × 60 段就是两千多次 stat，一天 900 段更多。体积只有确认对话框需要。
     */
    fun countDeletable(): Int {
        val active = activeDir()
        return SessionPaths.listSessionDirs().count { isDeletable(it, active) }
    }

    /** 列出可以安全删除的会话（含体积，会遍历文件），不做任何改动。 */
    fun listDeletable(): List<Candidate> {
        val active = activeDir()
        return SessionPaths.listSessionDirs()
            .filter { isDeletable(it, active) }
            .map { dir ->
                Candidate(dir, dir.name, dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() })
            }
    }

    fun deleteAll(candidates: List<Candidate>): Result {
        var deleted = 0
        var freed = 0L
        val failed = mutableListOf<String>()
        val active = activeDir()
        for (c in candidates) {
            // 删之前再确认一次：列表可能是几分钟前算的，而这中间可能已经开始录了
            if (c.dir.absolutePath == active) {
                failed.add("${c.displayName}（正在录制，跳过）")
                continue
            }
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
