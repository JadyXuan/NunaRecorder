package com.example.nunarecorder.sync

import com.example.nunarecorder.data.RecordingEntry
import com.example.nunarecorder.data.UserSettings
import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID

/**
 * 会话同步状态（供 UI 订阅）；IO 在后台协程执行。
 *
 * Upload flow: [docs/SESSION_SYNC_PROTOCOL.md] (section 2).
 */
object SessionSyncCoordinator {

    enum class Phase { SYNCING, DONE, ERROR }

    data class State(
        val targetKey: String,
        val displayName: String,
        val phase: Phase,
        val progress: Float,
        val message: String,
        val syncStatus: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    var onLog: ((String) -> Unit)? = null

    fun isActiveFor(targetKey: String): Boolean {
        val s = _state.value ?: return false
        return s.targetKey == targetKey && s.phase == Phase.SYNCING
    }

    fun start(
        entry: RecordingEntry,
        includeContext: Boolean,
        includeVad: Boolean,
        settings: UserSettings,
        httpClient: OkHttpClient
    ) {
        val key = when (entry) {
            is RecordingEntry.Session -> entry.dir.absolutePath
            is RecordingEntry.LegacyOpus -> entry.opusFile.absolutePath
        }
        if (isActiveFor(key)) {
            log("同步已在进行中")
            return
        }
        _state.value = State(key, entry.displayName, Phase.SYNCING, 0f, "准备同步…")
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    when (entry) {
                        is RecordingEntry.Session -> syncSession(
                            entry, includeContext, includeVad, settings, httpClient
                        )
                        is RecordingEntry.LegacyOpus -> syncLegacy(
                            entry, includeContext, settings, httpClient
                        )
                    }
                }
                if (result.success) {
                    complete(key, entry.displayName, result.message, result.finalStatus)
                } else {
                    fail(key, entry.displayName, result.message, result.finalStatus)
                }
            } catch (e: Exception) {
                fail(key, entry.displayName, e.message ?: "同步异常", "failed")
            }
        }
    }

    private data class SyncOutcome(
        val success: Boolean,
        val message: String,
        val finalStatus: String?
    )

    private fun syncSession(
        entry: RecordingEntry.Session,
        includeContext: Boolean,
        includeVad: Boolean,
        settings: UserSettings,
        httpClient: OkHttpClient
    ): SyncOutcome {
        val dir = entry.dir
        val manifest = SessionManifest.load(SessionPaths.manifestFile(dir)) ?: entry.manifest
        val inventory = SessionSyncInventory.buildRelative(dir, manifest, includeContext, includeVad)
        if (inventory.isEmpty()) {
            return SyncOutcome(false, "没有可同步的文件", "failed")
        }

        val clientUploadId = UUID.randomUUID().toString()
        var syncStatus = SessionSyncStatus(
            sessionId = manifest.sessionId,
            status = "syncing",
            clientUploadId = clientUploadId,
            files = inventory.toMutableList()
        )
        SessionSyncStatusIO.write(dir, syncStatus)

        val baseUrl = "http://${settings.serverHost}:${settings.serverPort}"
        val uploader = SessionSyncUploader(
            httpClient,
            baseUrl,
            settings.userId.ifBlank { "mock-user-001" },
            settings.mac.ifBlank { "AA:BB:CC:DD:EE:FF" }
        )

        update(dir.absolutePath, entry.displayName, 0.05f, "登记上传清单…", "syncing")

        val commit = uploader.tryV1Sync(
            dir,
            manifest,
            syncStatus.files,
            clientUploadId,
            onInit = { id ->
                syncStatus.uploadId = id
                SessionSyncStatusIO.write(dir, syncStatus)
            }
        ) { file, index, total ->
            val p = 0.1f + 0.8f * ((index + 1).toFloat() / total.coerceAtLeast(1))
            update(dir.absolutePath, entry.displayName, p, "上传 ${file.path} (${index + 1}/$total)", "syncing")
            SessionSyncStatusIO.write(dir, syncStatus)
        }

        if (commit.ok) {
            syncStatus.status = "synced"
            syncStatus.lastError = null
            SessionSyncStatusIO.write(dir, syncStatus)
            return SyncOutcome(true, commit.message, "synced")
        }

        if (commit.serverStatus == "v1_not_supported") {
            log("v1 同步不可用，回退逐文件上传")
            syncStatus.files.forEach { it.status = "pending"; it.error = null }
            return syncSessionFallback(dir, entry.displayName, syncStatus, uploader)
        }

        syncStatus.status = "partial"
        syncStatus.lastError = commit.message
        SessionSyncStatusIO.write(dir, syncStatus)
        return SyncOutcome(false, commit.message, "partial")
    }

    private fun syncSessionFallback(
        dir: File,
        displayName: String,
        syncStatus: SessionSyncStatus,
        uploader: SessionSyncUploader
    ): SyncOutcome {
        val pending = syncStatus.files.filter { it.status != "synced" }
        val total = pending.size.coerceAtLeast(1)
        pending.forEachIndexed { index, entry ->
            entry.status = "uploading"
            update(dir.absolutePath, displayName, 0.2f + 0.7f * (index + 1) / total, "回退上传 ${entry.path}", "syncing")
            val ok = uploader.fallbackLegacyUpload(File(dir, entry.path))
            entry.status = if (ok) "synced" else "failed"
            if (!ok) entry.error = "legacy upload failed"
            SessionSyncStatusIO.write(dir, syncStatus)
        }
        val failed = syncStatus.files.count { it.status == "failed" }
        syncStatus.status = if (failed == 0) "partial" else "partial"
        syncStatus.lastError = "服务端未升级 v1；已用旧接口上传（无 commit 确认）"
        SessionSyncStatusIO.write(dir, syncStatus)
        return if (failed == 0) {
            SyncOutcome(true, "已用旧接口上传（无服务器 commit 确认）", "partial")
        } else {
            SyncOutcome(false, "$failed 个文件上传失败", "partial")
        }
    }

    private fun syncLegacy(
        entry: RecordingEntry.LegacyOpus,
        includeContext: Boolean,
        settings: UserSettings,
        httpClient: OkHttpClient
    ): SyncOutcome {
        val files = mutableListOf(entry.opusFile)
        if (includeContext) {
            val bin = File(
                entry.opusFile.parentFile,
                "${entry.opusFile.nameWithoutExtension}${SessionPaths.LEGACY_BIN_SUFFIX}"
            )
            if (bin.exists()) files.add(bin)
        }
        val baseUrl = "http://${settings.serverHost}:${settings.serverPort}"
        val uploader = SessionSyncUploader(
            httpClient,
            baseUrl,
            settings.userId.ifBlank { "mock-user-001" },
            settings.mac.ifBlank { "AA:BB:CC:DD:EE:FF" }
        )
        var okCount = 0
        files.forEachIndexed { i, f ->
            update(entry.opusFile.absolutePath, entry.displayName, (i + 1f) / files.size, "上传 ${f.name}", null)
            if (uploader.fallbackLegacyUpload(f)) okCount++
        }
        return if (okCount == files.size) {
            SyncOutcome(true, "旧格式上传完成 ($okCount 文件)", "partial")
        } else {
            SyncOutcome(false, "上传失败 ${files.size - okCount}/${files.size}", "failed")
        }
    }

    private fun update(key: String, name: String, progress: Float, message: String, syncStatus: String?) {
        _state.value = State(key, name, Phase.SYNCING, progress.coerceIn(0f, 1f), message, syncStatus)
    }

    private fun complete(key: String, name: String, message: String, syncStatus: String?) {
        log(message)
        _state.value = State(key, name, Phase.DONE, 1f, message, syncStatus)
    }

    private fun fail(key: String, name: String, message: String, syncStatus: String?) {
        log(message)
        _state.value = State(key, name, Phase.ERROR, 0f, message, syncStatus)
    }

    internal fun log(msg: String) {
        onLog?.invoke(msg)
    }

    fun clearDoneState() {
        val s = _state.value ?: return
        if (s.phase == Phase.DONE || s.phase == Phase.ERROR) {
            _state.value = null
        }
    }
}
