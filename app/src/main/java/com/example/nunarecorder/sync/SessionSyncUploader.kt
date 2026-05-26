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
    private val deviceMac: String
) {

    data class CommitResult(
        val ok: Boolean,
        val serverStatus: String,
        val missing: List<String>,
        val message: String
    )

    fun tryV1Sync(
        sessionDir: File,
        manifest: SessionManifest,
        files: MutableList<SyncFileEntry>,
        clientUploadId: String,
        onInit: (uploadId: String) -> Unit = {},
        onFileProgress: (SyncFileEntry, index: Int, total: Int) -> Unit
    ): CommitResult {
        val initResp = postInit(sessionDir, manifest, files, clientUploadId)
        if (initResp == null) {
            return CommitResult(false, "v1_not_supported", emptyList(), "服务端未实现 v1 init")
        }
        val uploadId = initResp.getString("upload_id")
        onInit(uploadId)

        files.forEachIndexed { index, entry ->
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
            .url("$baseUrl/thingx/api/v1/session/sync/init")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                when (resp.code) {
                    404 -> null
                    else -> {
                        if (!resp.isSuccessful) return null
                        JSONObject(resp.body?.string() ?: return null)
                    }
                }
            }
        } catch (_: Exception) {
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
            .url("$baseUrl/thingx/api/v1/session/sync/file")
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
            .url("$baseUrl/thingx/api/v1/session/sync/commit")
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
