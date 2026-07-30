package com.example.nunarecorder.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.nunarecorder.data.UserSettings
import com.example.nunarecorder.data.UserSettingsStorage
import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionPaths
import com.example.nunarecorder.session.AudioSegmentEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 周期上传 manifest 中已经封口的 Opus segment。
 *
 * 录制中的 open segment 不会出现在 manifest.segments，因此不会读取仍在写入的文件。
 * 每个会话保留 path→sha256 状态；服务端同时收到稳定 Idempotency-Key。
 */
class SessionAutoUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = UserSettingsStorage(applicationContext).load()
        if (!settings.autoUploadEnabled) return@withContext Result.success()
        // 自动上传绝不能把私人录音落到共享的 mock 用户空间。
        if (settings.userId.isBlank()) return@withContext Result.success()

        var uploadedThisRun = 0
        var sawFailure = false
        val baseUrl = "http://${settings.serverHost}:${settings.serverPort}"

        // 先传旧会话，避免长时间积压；每轮限量控制耗电和后台运行时长。
        for (sessionDir in SessionPaths.listSessionDirs().asReversed()) {
            val manifest = SessionManifest.load(SessionPaths.manifestFile(sessionDir)) ?: continue
            val state = SessionAutoUploadState(sessionDir)
            val deviceMac = manifest.deviceAddress
                ?: SessionPaths.macFromSessionDirName(sessionDir.name)
                ?: "unknown"
            val uploader = SessionSyncUploader(
                client = OkHttpClient(),
                baseUrl = baseUrl,
                userId = settings.userId,
                deviceMac = deviceMac
            )

            for (segment in manifest.segments.sortedBy { it.index }) {
                if (uploadedThisRun >= MAX_SEGMENTS_PER_RUN) break
                val audioFile = File(sessionDir, segment.file)
                if (!audioFile.isFile || audioFile.length() <= 0L) continue
                if (!segment.integrityOk || audioFile.length() % OPUS_FRAME_BYTES != 0L) continue
                val sha256 = SessionSyncStatus.sha256(audioFile)
                if (state.isUploaded(segment.file, sha256)) continue

                val idempotencyKey = "${manifest.sessionId}:${segment.index}:$sha256"
                val remoteName = "${manifest.sessionId}_${audioFile.name}"
                val timeRange = uploadTimeRange(manifest, segment, audioFile.length())
                val ok = uploader.uploadAudioSegment(
                    file = audioFile,
                    remoteName = remoteName,
                    startTimeMs = timeRange.startTimeMs,
                    endTimeMs = timeRange.endTimeMs,
                    clientUploadId = idempotencyKey
                )
                if (!ok) {
                    sawFailure = true
                    break
                }
                state.markUploaded(segment.file, sha256)
                uploadedThisRun++
            }
            if (sawFailure || uploadedThisRun >= MAX_SEGMENTS_PER_RUN) break
        }

        if (sawFailure) Result.retry() else Result.success()
    }

    companion object {
        private const val UNIQUE_WORK = "lifelog_auto_upload"
        private const val MAX_SEGMENTS_PER_RUN = 12
        private const val OPUS_FRAME_BYTES = 80L

        fun schedule(context: Context, settings: UserSettings) {
            val manager = WorkManager.getInstance(context)
            if (!settings.autoUploadEnabled) {
                manager.cancelUniqueWork(UNIQUE_WORK)
                return
            }
            val networkType = if (settings.autoUploadWifiOnly) {
                NetworkType.UNMETERED
            } else {
                NetworkType.CONNECTED
            }
            val request = PeriodicWorkRequestBuilder<SessionAutoUploadWorker>(
                15,
                TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(networkType)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            manager.enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}

internal data class UploadTimeRange(
    val startTimeMs: Long,
    val endTimeMs: Long
)

internal fun uploadTimeRange(
    manifest: SessionManifest,
    segment: AudioSegmentEntry,
    actualBytes: Long
): UploadTimeRange {
    val startTimeMs = manifest.startedAtMs + segment.startMs
    val frameCount = actualBytes / 80L
    return UploadTimeRange(
        startTimeMs = startTimeMs,
        endTimeMs = startTimeMs + frameCount * 20L
    )
}
