package com.example.nunarecorder.sync

import android.content.Context
import com.example.nunarecorder.data.RecordingEntry
import com.example.nunarecorder.enroll.EnrollmentCode
import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.service.UploadService
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 会话同步状态（供 UI 订阅）；IO 在后台协程执行。
 *
 * Upload flow: [docs/SESSION_SYNC_PROTOCOL.md] (section 2).
 */
object SessionSyncCoordinator {

    enum class Phase { SYNCING, DONE, ERROR, CANCELLED, TOKEN_REJECTED }

    /** 令牌被撤销时回调宿主，用于置位并提示参与者联系研究员 */
    var onTokenRejected: (() -> Unit)? = null

    /**
     * Application context，用来在上传期间开/关前台服务。
     *
     * 存的是 `applicationContext`，不是 Activity——这个对象活得比任何 Activity 都长，
     * 存 Activity 就是泄漏。宿主没设置时（单测）所有前台服务调用退化为空操作。
     */
    @Volatile
    private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 上传期间把进程钉在前台。
     *
     * 上传原来完全跑在没有前台组件的进程里：切走或锁屏之后系统可以随时限网、
     * 冻结甚至回收它，而一天 800 多个小文件本来就要传很久。
     * 用户报的"切界面就中断"应该是这个，**不是切界面本身**——
     * 这个协程挂在进程级 scope 上，切标签页碰不到它。
     */
    private fun startForegroundUpload(text: String) {
        appContext?.let { UploadService.ensureRunning(it, text) }
    }

    private fun updateForegroundUpload(text: String, progress: Int?) {
        appContext?.let { UploadService.updateProgress(it, text, progress) }
    }

    private fun stopForegroundUpload() {
        appContext?.let { UploadService.stop(it) }
    }

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

    /** 上传卡住时用户可以取消；下一次上传会从服务端已收到的地方接着传 */
    private val cancelRequested = AtomicBoolean(false)

    /** 当前上传器，取消时用它中止在途请求 */
    @Volatile
    private var activeUploader: SessionSyncUploader? = null

    var onLog: ((String) -> Unit)? = null

    fun isActiveFor(targetKey: String): Boolean {
        val s = _state.value ?: return false
        return s.targetKey == targetKey && s.phase == Phase.SYNCING
    }

    /**
     * 请求取消当前上传。已经传完的文件留在服务端，`sync_status.json` 停在
     * `partial` 而不是半截状态，下次点上传会复用同一个 `client_upload_id` 续传。
     */
    fun cancel() {
        if (_state.value?.phase != Phase.SYNCING) return
        cancelRequested.set(true)
        // 只置标记不够：卡在某个文件里的请求等不到下一次检查
        activeUploader?.cancelInFlight()
        log("正在取消上传…")
    }

    fun start(
        entry: RecordingEntry,
        includeContext: Boolean,
        includeVad: Boolean,
        enrollment: EnrollmentCode,
        httpClient: OkHttpClient,
        includeMmWave: Boolean = true
    ) {
        val key = when (entry) {
            is RecordingEntry.Session -> entry.dir.absolutePath
            is RecordingEntry.LegacyOpus -> entry.opusFile.absolutePath
        }
        if (_state.value?.phase == Phase.SYNCING) {
            log(
                if (isActiveFor(key)) "同步已在进行中"
                else "已有另一个会话正在同步，当前会话排队前请等待它结束"
            )
            return
        }
        cancelRequested.set(false)
        _state.value = State(key, entry.displayName, Phase.SYNCING, 0f, "准备同步…")
        startForegroundUpload("准备上传 ${entry.displayName}")
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    when (entry) {
                        is RecordingEntry.Session -> syncSession(
                            entry, includeContext, includeVad, enrollment, httpClient, includeMmWave
                        )
                        is RecordingEntry.LegacyOpus -> syncLegacy(
                            entry, includeContext, enrollment, httpClient
                        )
                    }
                }
                when {
                    result.success -> complete(key, entry.displayName, result.message, result.finalStatus)
                    result.finalStatus == "cancelled" ->
                        cancelled(key, entry.displayName, result.message)
                    result.finalStatus == "token_rejected" -> {
                        onTokenRejected?.invoke()
                        log(result.message)
                        _state.value = State(
                            key, entry.displayName, Phase.TOKEN_REJECTED, 0f, result.message, "partial"
                        )
                    }
                    else -> fail(key, entry.displayName, result.message, result.finalStatus)
                }
            } catch (e: Exception) {
                fail(key, entry.displayName, e.message ?: "同步异常", "failed")
            } finally {
                // 唯一的关闭点。放在各个终态分支里会漏——token_rejected 那条
                // 就不走 complete/fail/cancelled，前台通知会永远挂在状态栏上。
                // "开始置位、指望结束路径清零"这个模式本项目已经踩过两次。
                stopForegroundUpload()
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
        enrollment: EnrollmentCode,
        httpClient: OkHttpClient,
        includeMmWave: Boolean
    ): SyncOutcome {
        val dir = entry.dir
        val manifest = SessionManifest.load(SessionPaths.manifestFile(dir)) ?: entry.manifest
        val inventory = SessionSyncInventory.buildRelative(
            dir, manifest, includeContext, includeVad, includeMmWave
        )
        if (inventory.isEmpty()) {
            return SyncOutcome(false, "没有可同步的文件", "failed")
        }

        // 复用上一次的 client_upload_id，服务端才认得出这是续传。
        // 之前每次都新生成一个，于是重试等于把所有文件重传一遍。
        val plan = SessionSyncPlanner.plan(
            previous = SessionSyncStatus.load(dir),
            sessionId = manifest.sessionId,
            inventory = inventory
        )
        plan.restartReason?.let { log(it) }
        val clientUploadId = plan.clientUploadId
        if (plan.resumingPreviousUpload) {
            log("续传上一次上传（client_upload_id 复用）")
        }
        val syncStatus = SessionSyncStatus(
            sessionId = manifest.sessionId,
            status = "syncing",
            clientUploadId = clientUploadId,
            files = plan.files
        )
        SessionSyncStatusIO.write(dir, syncStatus)

        val uploader = SessionSyncUploader(
            httpClient,
            enrollment.serverUrl,
            enrollment.participantId,
            resolveDeviceMac(manifest),
            enrollment.token
        )

        activeUploader = uploader
        val commit = try {
            uploader.tryV1Sync(
            dir,
            manifest,
            syncStatus.files,
            clientUploadId,
            onInit = { id ->
                syncStatus.uploadId = id
                SessionSyncStatusIO.write(dir, syncStatus)
            },
            shouldCancel = { cancelRequested.get() }
            ) { file, index, total ->
                val p = 0.1f + 0.8f * ((index + 1).toFloat() / total.coerceAtLeast(1))
                update(dir.absolutePath, entry.displayName, p, "上传 ${file.path} (${index + 1}/$total)", "syncing")
                // 通知里给的是"第几个文件"，不是百分比——一天 800 多个文件时
                // 百分比几乎不动，看着像卡死了
                updateForegroundUpload(
                    "${entry.displayName} · ${index + 1}/$total",
                    (p * 100).toInt()
                )
                SessionSyncStatusIO.write(dir, syncStatus)
            }
        } finally {
            activeUploader = null
        }

        if (commit.ok) {
            syncStatus.status = "synced"
            syncStatus.lastError = null
            SessionSyncStatusIO.write(dir, syncStatus)
            return SyncOutcome(true, commit.message, "synced")
        }

        // 会话级 legacy 回退已删除。它会把 manifest/context/VAD 当成音频上传，
        // 并让每个会话的 seg_000.opus 互相覆盖、时间戳全部落到 1970-01-01，
        // 而服务端全程返回 success。实测证据见
        // ../doc/status/PIPELINE_STATUS_2026-07-27.md §3。
        // 服务端没有 v1 时正确的做法是停下来报错，把数据留在手机上。

        // token_rejected 也记成 partial：本地文件保留，等重新入组后可以续传
        syncStatus.status = "partial"
        syncStatus.lastError = commit.message
        SessionSyncStatusIO.write(dir, syncStatus)
        // client_upload_id 留在 sync_status.json 里：下一次点上传会带着它去 init，
        // 服务端返回 resumed=true 和已收文件清单，只补缺失的。
        return SyncOutcome(false, commit.message, commit.serverStatus.takeIf { it == "cancelled" } ?: "partial")
    }

    private fun syncLegacy(
        entry: RecordingEntry.LegacyOpus,
        includeContext: Boolean,
        enrollment: EnrollmentCode,
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
        val uploader = SessionSyncUploader(
            httpClient,
            enrollment.serverUrl,
            enrollment.participantId,
            resolveDeviceMac(null, entry.opusFile),
            enrollment.token
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

    private fun cancelled(key: String, name: String, message: String) {
        log(message)
        _state.value = State(key, name, Phase.CANCELLED, 0f, message, "partial")
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

    /** 优先 manifest.device_address；旧目录名若为 MAC 格式则解析 */
    private fun resolveDeviceMac(manifest: SessionManifest?, legacyFile: File? = null): String {
        manifest?.deviceAddress?.takeIf { it.isNotBlank() }?.let { return it }
        manifest?.sessionId?.let { SessionPaths.macFromSessionDirName(it) }?.let { return it }
        legacyFile?.nameWithoutExtension?.let { SessionPaths.macFromSessionDirName(it) }
            ?.let { return it }
        return "unknown"
    }

    /**
     * 一键上传所有未同步会话。
     *
     * **按 `started_at_ms` 升序排队，前一个 commit 成功才传下一个。**
     * 这不是为了整齐：服务端的标注块成员资格是 `audio_data.id` 区间，而 id 是入库顺序。
     * 同一天的会话乱序上传会让 id 顺序不等于时间顺序，一个跨越两段的标注块就会框进
     * 时间上不连续的内容，而界面按 start_ts 排序显示，看起来完全正常。
     * 见 doc/handoff/2026-08-03-data-platform-fix-prompt.md 问题 2。
     */
    fun startBatch(
        entries: List<RecordingEntry>,
        includeContext: Boolean,
        includeVad: Boolean,
        enrollment: EnrollmentCode,
        httpClient: OkHttpClient
    ) {
        if (_state.value?.phase == Phase.SYNCING) {
            log("已有上传在进行中")
            return
        }
        val ordered = entries.sortedBy {
            when (it) {
                is RecordingEntry.Session -> it.manifest.startedAtMs
                is RecordingEntry.LegacyOpus -> it.opusFile.lastModified()
            }
        }
        if (ordered.isEmpty()) {
            log("没有需要上传的会话")
            return
        }
        cancelRequested.set(false)
        log("开始批量上传 ${ordered.size} 个会话（按录制时间先后）")
        startForegroundUpload("准备上传 ${ordered.size} 个会话")
        scope.launch {
          try {
            var done = 0
            for (entry in ordered) {
                if (cancelRequested.get()) {
                    log("批量上传已取消，已完成 $done/${ordered.size}")
                    break
                }
                val key = keyOf(entry)
                _state.value = State(
                    key, entry.displayName, Phase.SYNCING, 0f,
                    "上传 ${done + 1}/${ordered.size}：${entry.displayName}"
                )
                updateForegroundUpload(
                    "第 ${done + 1}/${ordered.size} 个会话：${entry.displayName}",
                    null
                )
                val result = withContext(Dispatchers.IO) {
                    when (entry) {
                        is RecordingEntry.Session ->
                            syncSession(entry, includeContext, includeVad, enrollment, httpClient, true)
                        is RecordingEntry.LegacyOpus ->
                            syncLegacy(entry, includeContext, enrollment, httpClient)
                    }
                }
                if (!result.success) {
                    // 前一个没成功就停下：继续传后面的会打乱入库顺序
                    log("在 ${entry.displayName} 上停止：${result.message}")
                    if (result.finalStatus == "token_rejected") onTokenRejected?.invoke()
                    _state.value = State(key, entry.displayName, Phase.ERROR, 0f, result.message)
                    return@launch
                }
                done++
            }
            log("批量上传完成 $done/${ordered.size}")
            _state.value = State(
                keyOf(ordered.last()), "批量上传", Phase.DONE, 1f, "已上传 $done 个会话"
            )
          } finally {
            // 同上：中途 return@launch 的失败分支也要关掉前台服务
            stopForegroundUpload()
          }
        }
    }

    private fun keyOf(entry: RecordingEntry): String = when (entry) {
        is RecordingEntry.Session -> entry.dir.absolutePath
        is RecordingEntry.LegacyOpus -> entry.opusFile.absolutePath
    }

    fun clearDoneState() {
        val s = _state.value ?: return
        if (s.phase == Phase.DONE || s.phase == Phase.ERROR ||
            s.phase == Phase.CANCELLED || s.phase == Phase.TOKEN_REJECTED
        ) {
            _state.value = null
        }
    }
}
