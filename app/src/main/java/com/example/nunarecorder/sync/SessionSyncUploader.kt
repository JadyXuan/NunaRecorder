package com.example.nunarecorder.sync

import com.example.nunarecorder.audio.AudioMetaUtil
import com.example.nunarecorder.session.SessionManifest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

class SessionSyncUploader(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val userId: String,
    private val deviceMac: String,
    /** 对应 Receiver 的 UPLOAD_TOKEN；为空时服务端返回 503 */
    private val uploadToken: String = ""
) {

    /**
     * EgoAudio 自有命名空间。历史上这里是 `/thingx/api/v1/...`，那是旧 Nuna 官方
     * 接口的路径，不是本项目的契约（见 ../doc/plan/TODO.md P0）。
     */
    private fun v1(path: String): String = "$baseUrl/v1/session/sync/$path"

    private fun Request.Builder.withToken(): Request.Builder =
        if (uploadToken.isNotBlank()) header("X-Upload-Token", uploadToken) else this

    data class CommitResult(
        val ok: Boolean,
        val serverStatus: String,
        val missing: List<String>,
        val message: String
    )

    /** 最近一次 init 的 HTTP 状态与响应体，用于把失败原因显示给用户而不是静默回退。 */
    var lastInitCode: Int = 0
        private set
    var lastInitBody: String = ""
        private set

    fun tryV1Sync(
        sessionDir: File,
        manifest: SessionManifest,
        files: MutableList<SyncFileEntry>,
        clientUploadId: String,
        onInit: (uploadId: String) -> Unit = {},
        /** 返回 true 时中止本次上传；已传完的文件保留在服务端，下次续传 */
        shouldCancel: () -> Boolean = { false },
        onFileProgress: (SyncFileEntry, index: Int, total: Int) -> Unit
    ): CommitResult {
        val initResp = postInit(sessionDir, manifest, files, clientUploadId)
        if (initResp == null) {
            val reason = when (lastInitCode) {
                404 -> "服务端未实现 v1 同步（404）。请升级 Receiver，不要用旧接口上传会话。"
                401 -> "上传令牌无效（401）。请在设置里填写正确的上传令牌。"
                503 -> "服务端未配置上传令牌（503）。请先在 Receiver 上设置 UPLOAD_TOKEN。"
                -1 -> "无法连接服务器：$lastInitBody"
                else -> "init 失败（HTTP $lastInitCode）：${lastInitBody.take(160)}"
            }
            return CommitResult(false, "init_failed", emptyList(), reason)
        }
        val uploadId = initResp.getString("upload_id")
        onInit(uploadId)

        // 服务端认出这是同一次上传时会返回 resumed=true。此时以**服务端**的
        // 文件状态为准，而不是本机 sync_status.json——本机记录可能停在崩溃前那一刻。
        if (initResp.optBoolean("resumed", false)) {
            val serverFiles = getStatus(uploadId)
            if (serverFiles != null) {
                val skipped = SessionSyncPlanner.applyServerState(files, serverFiles)
                SessionSyncCoordinator.log("服务端已有 $skipped/${files.size} 个文件，只补缺失的")
            }
        }

        files.forEachIndexed { index, entry ->
            if (shouldCancel()) return CommitResult(false, "cancelled", emptyList(), "上传已取消")
            if (entry.status == "synced") {
                onFileProgress(entry, index, files.size)
                return@forEachIndexed
            }
            entry.status = "uploading"
            onFileProgress(entry, index, files.size)
            val f = File(sessionDir, entry.path)
            val ok = postFile(uploadId, entry, f)
            if (ok) {
                entry.status = "synced"
                entry.uploadedAtMs = System.currentTimeMillis()
                entry.error = null
            } else {
                entry.status = "failed"
                entry.error = "upload failed"
            }
            onFileProgress(entry, index, files.size)
        }

        if (shouldCancel()) return CommitResult(false, "cancelled", emptyList(), "上传已取消")

        val failed = files.filter { it.status != "synced" }
        if (failed.isNotEmpty()) {
            return CommitResult(
                false,
                "partial",
                failed.map { it.path },
                "${failed.size} 个文件上传失败"
            )
        }

        return postCommit(uploadId, clientUploadId, files)
    }

    /** `GET /v1/session/sync/status?upload_id=`：服务端已经收到了哪些文件。 */
    fun getStatus(uploadId: String): List<ServerFileState>? {
        val request = Request.Builder()
            .url("${v1("status")}?upload_id=$uploadId")
            .withToken()
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val arr = JSONObject(resp.body?.string() ?: "{}").optJSONArray("files")
                    ?: return emptyList()
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    ServerFileState(
                        path = o.optString("path"),
                        status = o.optString("status"),
                        sha256 = o.optString("sha256").lowercase()
                    )
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 旧接口逐文件上传（无会话级确认） */
    fun fallbackLegacyUpload(file: File): Boolean {
        val startTimeMs = AudioMetaUtil.parseStartTimeFromFileName(file.name) ?: file.lastModified()
        val durationMs = if (file.extension.equals("opus", ignoreCase = true)) {
            AudioMetaUtil.computeDurationMsForOpusFile(file)
        } else 0L
        val endTimeMs = startTimeMs + durationMs
        val metadataJson = JSONObject().apply {
            put("userId", userId)
            put("name", file.name)
            put("startTime", startTimeMs)
            put("endTime", endTimeMs)
            put("mac", deviceMac)
            put("size", file.length())
        }.toString()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("application/octet-stream".toMediaType()))
            .addFormDataPart(
                "metadata",
                "metadata.json",
                metadataJson.toRequestBody("text/plain".toMediaType())
            )
            .build()
        val request = Request.Builder()
            .url("$baseUrl/thingx/api/file/upload/audio")
            .post(body)
            .build()
        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private fun postInit(
        sessionDir: File,
        manifest: SessionManifest,
        files: List<SyncFileEntry>,
        clientUploadId: String
    ): JSONObject? {
        val manifestJson = SessionManifest.load(
            com.example.nunarecorder.session.SessionPaths.manifestFile(sessionDir)
        )?.toJson() ?: manifest.toJson()
        val filesArr = JSONArray().apply {
            files.forEach { f ->
                put(JSONObject().apply {
                    put("path", f.path)
                    put("sha256", f.sha256)
                    put("size", f.size)
                    put("media_type", f.mediaType)
                })
            }
        }
        val body = JSONObject().apply {
            put("client_upload_id", clientUploadId)
            put("session_id", manifest.sessionId)
            put("user_id", userId)
            put("device_mac", deviceMac)
            put("started_at_ms", manifest.startedAtMs)
            put("ended_at_ms", manifest.endedAtMs ?: JSONObject.NULL)
            put("manifest", manifestJson)
            put("files", filesArr)
        }.toString()
        val request = Request.Builder()
            .url(v1("init"))
            .withToken()
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                lastInitCode = resp.code
                lastInitBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return null
                JSONObject(lastInitBody.ifBlank { return null })
            }
        } catch (e: Exception) {
            lastInitCode = -1
            lastInitBody = e.message.orEmpty()
            null
        }
    }

    private fun postFile(uploadId: String, entry: SyncFileEntry, file: File): Boolean {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("upload_id", uploadId)
            .addFormDataPart("relative_path", entry.path)
            .addFormDataPart("sha256", entry.sha256)
            .addFormDataPart("file", file.name, file.asRequestBody(entry.mediaType.toMediaType()))
            .build()
        val request = Request.Builder()
            .url(v1("file"))
            .withToken()
            .post(body)
            .build()
        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private fun postCommit(
        uploadId: String,
        clientUploadId: String,
        files: List<SyncFileEntry>
    ): CommitResult {
        val filesArr = JSONArray().apply {
            files.forEach { f ->
                put(JSONObject().apply {
                    put("path", f.path)
                    put("sha256", f.sha256)
                })
            }
        }
        val body = JSONObject().apply {
            put("upload_id", uploadId)
            put("client_upload_id", clientUploadId)
            put("files", filesArr)
        }.toString()
        val request = Request.Builder()
            .url(v1("commit"))
            .withToken()
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (resp.code == 404) {
                    return CommitResult(
                        false,
                        "partial",
                        emptyList(),
                        "服务端未实现 v1 commit（404）"
                    )
                }
                if (!resp.isSuccessful) {
                    return CommitResult(false, "failed", emptyList(), "commit HTTP ${resp.code}")
                }
                val j = JSONObject(resp.body?.string() ?: "{}")
                val status = j.optString("status", "partial")
                val missing = mutableListOf<String>()
                j.optJSONArray("missing")?.let { arr ->
                    for (i in 0 until arr.length()) missing.add(arr.getString(i))
                }
                val ok = status == "synced" && missing.isEmpty()
                CommitResult(
                    ok = ok,
                    serverStatus = status,
                    missing = missing,
                    message = if (ok) "服务器已确认同步" else "commit: $status missing=$missing"
                )
            }
        } catch (e: Exception) {
            CommitResult(false, "failed", emptyList(), e.message ?: "commit 异常")
        }
    }
}
