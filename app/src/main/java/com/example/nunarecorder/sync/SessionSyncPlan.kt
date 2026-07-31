package com.example.nunarecorder.sync

import java.util.UUID

/** 服务端 `GET /v1/session/sync/status` 返回的单个文件状态。 */
data class ServerFileState(
    val path: String,
    val status: String,
    val sha256: String
) {
    val received: Boolean get() = status == "received"
}

data class SyncPlan(
    val clientUploadId: String,
    /** true = 复用了上一次的 upload，服务端会返回 `resumed: true` */
    val resumingPreviousUpload: Boolean,
    val files: MutableList<SyncFileEntry>,
    /** 为什么没能复用，写进日志便于排查 */
    val restartReason: String? = null
)

/**
 * 决定这次同步用哪个 `client_upload_id`，以及哪些文件还需要传。
 *
 * 2026-07-31 实测：客户端每次点上传都 `UUID.randomUUID()` 生成新的 `client_upload_id`，
 * 服务端认不出这是同一次上传，于是所有文件重传一遍。协议本来就支持续传，客户端没用上。
 *
 * **复用有一个硬条件：清单必须完全一致。** 服务端在 `init` 命中已有
 * `client_upload_id` 时会直接返回旧的 upload，**不会**用新清单去注册文件行。
 * 如果上传失败后会话又多录了几段就复用旧 id，新分段永远不会被登记，
 * commit 仍按旧清单返回 `synced`，客户端把会话标成已同步——数据就这么没了。
 * 清单一变就重新开一次 upload。
 */
object SessionSyncPlanner {

    fun plan(
        previous: SessionSyncStatus?,
        sessionId: String,
        inventory: List<SyncFileEntry>,
        newClientUploadId: () -> String = { UUID.randomUUID().toString() }
    ): SyncPlan {
        val fresh = { reason: String? ->
            SyncPlan(
                clientUploadId = newClientUploadId(),
                resumingPreviousUpload = false,
                files = inventory.map { it.copy(status = "pending", error = null) }.toMutableList(),
                restartReason = reason
            )
        }

        val previousId = previous?.clientUploadId?.takeIf { it.isNotBlank() }
            ?: return fresh(null)

        if (previous.sessionId != sessionId) {
            return fresh("上一次同步记录属于另一个会话")
        }
        if (!inventoryMatches(previous, inventory)) {
            return fresh("文件清单已变化（会话在上次上传后又录了新内容），重新登记一次上传")
        }

        // 本机记录里已经确认过的文件先标成 synced；服务端 status 会再校正一次
        val byPath = previous.files.associateBy { it.path }
        val files = inventory.map { entry ->
            val known = byPath[entry.path]
            val alreadySynced = known != null &&
                known.status == "synced" &&
                known.sha256 == entry.sha256
            entry.copy(
                status = if (alreadySynced) "synced" else "pending",
                uploadedAtMs = if (alreadySynced) known?.uploadedAtMs else null,
                error = null
            )
        }.toMutableList()

        return SyncPlan(
            clientUploadId = previousId,
            resumingPreviousUpload = true,
            files = files
        )
    }

    /** 路径集合与每个路径的 sha256 都相同才算同一份清单。 */
    fun inventoryMatches(previous: SessionSyncStatus, inventory: List<SyncFileEntry>): Boolean {
        if (previous.files.size != inventory.size) return false
        val previousByPath = previous.files.associate { it.path to it.sha256 }
        return inventory.all { previousByPath[it.path] == it.sha256 }
    }

    /**
     * 用服务端的实际状态校正本机记录：只有服务端确认收到且 sha256 一致的才跳过。
     *
     * @return 因此可以跳过的文件数
     */
    fun applyServerState(
        files: MutableList<SyncFileEntry>,
        serverFiles: List<ServerFileState>
    ): Int {
        val byPath = serverFiles.associateBy { it.path }
        var skipped = 0
        for (i in files.indices) {
            val entry = files[i]
            val server = byPath[entry.path]
            val done = server != null && server.received && server.sha256 == entry.sha256
            files[i] = entry.copy(
                status = if (done) "synced" else "pending",
                error = null
            )
            if (done) skipped++
        }
        return skipped
    }
}
