package com.example.nunarecorder.sync

import com.example.nunarecorder.util.writeTextAtomic
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class SyncFileEntry(
    val path: String,
    val sha256: String,
    val size: Long,
    val mediaType: String,
    var status: String = "pending",
    var uploadedAtMs: Long? = null,
    var error: String? = null
)

/**
 * 本机 `labels/sync_status.json`（客户端维护，记录上传进度与结果）。
 *
 * Schema: [docs/SESSION_SYNC_PROTOCOL.md] (section 1.3).
 */
data class SessionSyncStatus(
    val formatVersion: Int = 1,
    val sessionId: String,
    var status: String = "none",
    var uploadId: String? = null,
    var serverSessionId: String? = null,
    var clientUploadId: String? = null,
    var updatedAtMs: Long = System.currentTimeMillis(),
    val files: MutableList<SyncFileEntry> = mutableListOf(),
    var lastError: String? = null
) {
    val summary: Summary get() {
        val total = files.size
        val synced = files.count { it.status == "synced" }
        val failed = files.count { it.status == "failed" }
        val pending = files.count { it.status in setOf("pending", "uploading") }
        return Summary(total, synced, failed, pending)
    }

    data class Summary(val total: Int, val synced: Int, val failed: Int, val pending: Int)

    fun toJson(): JSONObject = JSONObject().apply {
        put("format_version", formatVersion)
        put("session_id", sessionId)
        put("status", status)
        put("upload_id", uploadId ?: JSONObject.NULL)
        put("server_session_id", serverSessionId ?: JSONObject.NULL)
        put("client_upload_id", clientUploadId ?: JSONObject.NULL)
        put("updated_at_ms", updatedAtMs)
        put("last_error", lastError ?: JSONObject.NULL)
        put("summary", JSONObject().apply {
            put("total", summary.total)
            put("synced", summary.synced)
            put("failed", summary.failed)
            put("pending", summary.pending)
        })
        put("files", JSONArray().apply {
            files.forEach { f ->
                put(JSONObject().apply {
                    put("path", f.path)
                    put("sha256", f.sha256)
                    put("size", f.size)
                    put("media_type", f.mediaType)
                    put("status", f.status)
                    put("uploaded_at_ms", f.uploadedAtMs ?: JSONObject.NULL)
                    put("error", f.error ?: JSONObject.NULL)
                })
            }
        })
    }

    companion object {
        fun load(sessionDir: File): SessionSyncStatus? {
            return try {
                val f = syncFile(sessionDir)
                if (!f.exists()) return null
                val j = JSONObject(f.readText())
                val files = mutableListOf<SyncFileEntry>()
                val arr = j.optJSONArray("files") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    files.add(
                        SyncFileEntry(
                            path = o.getString("path"),
                            sha256 = o.getString("sha256"),
                            size = o.getLong("size"),
                            mediaType = o.optString("media_type", "application/octet-stream"),
                            status = o.optString("status", "pending"),
                            uploadedAtMs = o.optLong("uploaded_at_ms").takeIf {
                                o.has("uploaded_at_ms") && !o.isNull("uploaded_at_ms")
                            },
                            error = o.optString("error").takeIf { it.isNotEmpty() }
                        )
                    )
                }
                SessionSyncStatus(
                    sessionId = j.getString("session_id"),
                    status = j.optString("status", "none"),
                    uploadId = j.optString("upload_id").takeIf { it.isNotEmpty() },
                    serverSessionId = j.optString("server_session_id").takeIf { it.isNotEmpty() },
                    clientUploadId = j.optString("client_upload_id").takeIf { it.isNotEmpty() },
                    updatedAtMs = j.optLong("updated_at_ms", System.currentTimeMillis()),
                    files = files,
                    lastError = j.optString("last_error").takeIf { it.isNotEmpty() }
                )
            } catch (_: Exception) {
                null
            }
        }

        fun syncFile(sessionDir: File): File = File(sessionDir, "labels/sync_status.json")

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } > 0) {
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

object SessionSyncStatusIO {
    fun write(sessionDir: File, status: SessionSyncStatus) {
        File(sessionDir, "labels").mkdirs()
        status.updatedAtMs = System.currentTimeMillis()
        SessionSyncStatus.syncFile(sessionDir).writeTextAtomic(status.toJson().toString(2))
    }
}
