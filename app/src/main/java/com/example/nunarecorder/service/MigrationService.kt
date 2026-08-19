package com.example.nunarecorder.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.example.nunarecorder.migration.LegacySessionMigrator
import com.example.nunarecorder.migration.MigrateOptions
import com.example.nunarecorder.migration.MigrationCoordinator
import com.example.nunarecorder.util.BatteryOptimizationHelper
import java.io.File

/**
 * 前台服务：在后台完成旧版 .opus 切分会话目录，并触发 VAD 队列。
 */
class MigrationService : Service() {

    companion object {
        private const val TAG = "MigrationService"

        const val ACTION_START = "com.example.nunarecorder.action.START_MIGRATION"
        const val EXTRA_OPUS_PATH = "extra_opus_path"
        const val EXTRA_DO_SPLIT = "extra_do_split"
        const val EXTRA_DO_VAD = "extra_do_vad"
        const val EXTRA_SEGMENT_SEC = "extra_segment_sec"

        @Volatile
        private var migrationRunning = false

        fun enqueue(context: Context, opusPath: String, options: MigrateOptions = MigrateOptions()) {
            val app = context.applicationContext
            ProcessingNotifications.ensureChannels(app)
            if (!BatteryOptimizationHelper.isIgnoringOptimizations(app)) {
                Log.w(TAG, "未忽略电池优化，息屏后任务可能被系统终止")
            }
            runCatching {
                app.startForegroundService(
                    Intent(app, MigrationService::class.java).apply {
                        action = ACTION_START
                        putExtra(EXTRA_OPUS_PATH, opusPath)
                        putExtra(EXTRA_DO_SPLIT, options.doSplit)
                        putExtra(EXTRA_DO_VAD, options.doVad)
                        putExtra(EXTRA_SEGMENT_SEC, options.segmentDurationSec)
                    }
                )
            }.onFailure {
                Log.w(TAG, "无法启动迁移前台服务：${it.message}")
                MigrationCoordinator.fail(
                    opusPath, File(opusPath).name, "迁移未启动：前台处理额度暂不可用"
                )
            }
        }
    }

    private var serviceWakeLock: PowerManager.WakeLock? = null

    /** Prevents a timed-out service instance from promoting itself again via worker callbacks. */
    @Volatile
    private var timedOut = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ProcessingNotifications.ensureChannels(this)
        acquireServiceWakeLock()
        promoteToForeground("准备迁移录音…", 0, indeterminate = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val path = intent.getStringExtra(EXTRA_OPUS_PATH)
                if (path.isNullOrBlank()) {
                    Log.e(TAG, "START without opus path")
                    stopSelf()
                    return START_NOT_STICKY
                }
                val opus = File(path)
                if (migrationRunning) {
                    Log.w(TAG, "reject: migration already running for ${opus.name}")
                    MigrationCoordinator.fail(
                        path,
                        opus.name,
                        "已有迁移任务未完成，请完全退出 App 后重试"
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }
                val options = MigrateOptions(
                    doSplit = intent.getBooleanExtra(EXTRA_DO_SPLIT, true),
                    doVad = intent.getBooleanExtra(EXTRA_DO_VAD, true),
                    segmentDurationSec = intent.getIntExtra(EXTRA_SEGMENT_SEC, 60).coerceIn(10, 600)
                )
                timedOut = false
                migrationRunning = true
                Log.d(TAG, "start migration: ${opus.name} split=${options.doSplit} vad=${options.doVad}")
                acquireServiceWakeLock()
                promoteToForeground("准备迁移…", 0, indeterminate = true)
                runMigration(path, options)
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "dataSync 前台额度耗尽，撤下迁移保活；已写文件保留且任务不会并发重开")
        DataSyncTimeoutStopper(
            cleanup = {
                timedOut = true
                releaseServiceWakeLock()
            },
            removeForeground = { stopForeground(STOP_FOREGROUND_REMOVE) },
            stopService = { stopSelf() }
        ).stop()
    }


    private fun runMigration(opusPath: String, options: MigrateOptions) {
        val opus = File(opusPath)
        val displayName = opus.name
        val appCtx = applicationContext
        BackgroundProcessing.onJobScheduled(appCtx)
        ProcessingExecutor.execute {
            Log.d(TAG, "migration task running on NunaMigration thread")
            try {
                val result = LegacySessionMigrator.migrate(
                    legacyOpus = opus,
                    options = options,
                    onLog = { MigrationCoordinator.log(it) },
                    onProgress = { phase, current, total ->
                        val (coordPhase, progress, msg) = when (phase) {
                            LegacySessionMigrator.ProgressPhase.VAD_ONLY -> Triple(
                                MigrationCoordinator.Phase.VAD_ONLY,
                                0.95f,
                                "续传 VAD…"
                            )
                            LegacySessionMigrator.ProgressPhase.SPLITTING -> {
                                val p = if (total > 0) current.toFloat() / total else 0f
                                Triple(
                                    MigrationCoordinator.Phase.SPLITTING,
                                    p * 0.9f,
                                    "切分音频 $current / $total"
                                )
                            }
                            LegacySessionMigrator.ProgressPhase.FINISHING -> Triple(
                                MigrationCoordinator.Phase.SPLITTING,
                                0.92f,
                                "写入 manifest…"
                            )
                        }
                        MigrationCoordinator.updateProgress(
                            opusPath, displayName, coordPhase, progress, msg
                        )
                        val pct = (progress * 100).toInt()
                        promoteToForeground(msg, pct)
                    }
                )
                MigrationCoordinator.complete(opusPath, displayName, result.message)
                promoteToForeground("完成: ${result.segmentCount} 段", 100)
            } catch (e: Exception) {
                MigrationCoordinator.fail(
                    opusPath,
                    displayName,
                    "迁移失败: ${e.message ?: e.javaClass.simpleName}"
                )
                promoteToForeground("迁移失败", 0)
            } finally {
                migrationRunning = false
                BackgroundProcessing.onJobFinished(appCtx)
                releaseServiceWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        releaseServiceWakeLock()
        // The executor, not the Service instance, owns the migration. A timeout
        // destroys this instance while the worker may still be completing safe,
        // resumable files; only the worker's finally block clears migrationRunning.
        super.onDestroy()
    }

    private fun acquireServiceWakeLock() {
        if (serviceWakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        serviceWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NunaRecorder:MigrationFg").apply {
            setReferenceCounted(false)
            acquire()
        }
        ProcessingWakeLock.ensureHeld(applicationContext)
    }

    private fun releaseServiceWakeLock() {
        serviceWakeLock?.let {
            if (it.isHeld) {
                try {
                    it.release()
                } catch (_: RuntimeException) {
                }
            }
        }
        serviceWakeLock = null
    }

    private fun promoteToForeground(content: String, percent: Int, indeterminate: Boolean = false) {
        if (timedOut) return
        val notification = ProcessingNotifications.buildMigration(this, content, percent, indeterminate)
        ForegroundServiceHelper.startDataSync(
            this,
            ProcessingNotifications.MIGRATION_NOTIFICATION_ID,
            notification
        )
    }
}
