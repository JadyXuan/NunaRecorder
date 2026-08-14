package com.example.nunarecorder.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * 上传期间把进程钉在前台。
 *
 * ## 先说清楚查到的事实，因为它和用户的猜测不一样
 *
 * 用户 2026-08-09：「中断似乎也是在上传过程中切换到其他界面（比如设备）」。
 *
 * **切界面不可能取消上传。** [com.example.nunarecorder.sync.SessionSyncCoordinator]
 * 是进程级单例，用自己的 `CoroutineScope(SupervisorJob())`，既不挂在 Activity
 * 也不挂在 Composition 上；全仓库唯一调用 `cancel()` 的地方是用户手点的那个取消按钮。
 * 切标签页只是把 `RecordingsScreen` 销毁重建，协程不受影响。
 *
 * 真正会打断它的是**另一件事**：上传原来完全跑在没有任何前台组件的进程里。
 * 一旦 App 退到后台或者锁屏，系统就可以随时限制网络、冻结进程，或者直接回收它——
 * 而一天 800 多个小文件本来就要传很久，正好落在这个窗口里。
 * 用户看到的"切了界面就断"，更可能是"切走之后过一会儿被系统掐了"。
 *
 * 所以修法不是去改导航，是**给上传一个前台服务**：有常驻通知、有 WakeLock，
 * 锁屏和切到别的 App 都不会被回收。断点续传本来就有
 * （`client_upload_id` 复用 + 服务端 `resumed` 清单），这里只负责别被杀。
 *
 * 服务本身**不做任何上传逻辑**，只管生命周期和通知。上传仍然归 Coordinator，
 * 免得同一件事有两个所有者。
 */
class UploadService : Service() {

    companion object {
        private const val TAG = "UploadService"

        const val ACTION_START = "com.example.nunarecorder.action.START_UPLOAD_FG"
        const val ACTION_STOP = "com.example.nunarecorder.action.STOP_UPLOAD_FG"
        private const val EXTRA_TEXT = "text"

        @Volatile
        private var foregroundActive = false

        /** 上传开始时调用；重复调用是安全的 */
        fun ensureRunning(context: Context, text: String) {
            val app = context.applicationContext
            ProcessingNotifications.ensureChannels(app)
            if (!ProcessingNotifications.canPostNotifications(app)) {
                // 没有通知权限时前台服务通知不可见，但服务照样起得来——
                // 保活仍然生效，只是参与者看不到进度。自检里已经就此告警。
                Log.w(TAG, "通知权限未授予，上传进度通知不可见")
            }
            runCatching {
                app.startForegroundService(
                    Intent(app, UploadService::class.java).apply {
                        action = ACTION_START
                        putExtra(EXTRA_TEXT, text)
                    }
                )
            }.onFailure { Log.w(TAG, "启动上传前台服务失败：${it.message}") }
        }

        /** 只更新通知，不重新启动服务 */
        fun updateProgress(context: Context, text: String, progress: Int? = null) {
            if (!foregroundActive) return
            ProcessingNotifications.updateUpload(context.applicationContext, text, progress)
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            runCatching {
                app.startService(
                    Intent(app, UploadService::class.java).apply { action = ACTION_STOP }
                )
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ProcessingNotifications.ensureChannels(this)
        acquireWakeLock()
        promoteToForeground("正在上传…")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseWakeLock()
                foregroundActive = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                // 上传已经结束，被系统重启没有意义，这里明确不粘。
                return START_NOT_STICKY
            }
            else -> {
                acquireWakeLock()
                promoteToForeground(intent?.getStringExtra(EXTRA_TEXT) ?: "正在上传…")
            }
        }
        // START_NOT_STICKY：进程真被杀掉时，没有 Coordinator 的协程可保活，
        // 光把服务重启起来只会显示一个不动的通知，比不重启更容易误导。
        // 未传完的部分留在 sync_status.json 里，下次点上传会续传。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        foregroundActive = false
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NunaRecorder:UploadFgService")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) runCatching { it.release() }
        }
        wakeLock = null
    }

    private fun promoteToForeground(content: String) {
        foregroundActive = true
        val notification = ProcessingNotifications.buildUpload(this, content)
        ForegroundServiceHelper.startDataSync(
            this,
            ProcessingNotifications.UPLOAD_NOTIFICATION_ID,
            notification
        )
    }
}
