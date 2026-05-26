package com.example.nunarecorder.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * 前台服务：锁屏/息屏时保持进程优先级；本服务持有独立 WakeLock。
 */
class VadProcessingService : Service() {

    companion object {
        private const val TAG = "VadProcessingService"

        const val ACTION_START = "com.example.nunarecorder.action.START_VAD_FG"
        const val ACTION_STOP = "com.example.nunarecorder.action.STOP_VAD_FG"

        @Volatile
        private var foregroundActive = false

        fun ensureRunning(context: Context) {
            val app = context.applicationContext
            ProcessingNotifications.ensureChannels(app)
            if (!ProcessingNotifications.canPostNotifications(app)) {
                Log.w(TAG, "通知权限未授予，前台服务通知可能不可见")
            }
            app.startForegroundService(
                Intent(app, VadProcessingService::class.java).apply { action = ACTION_START }
            )
        }

        fun stopIfIdle(context: Context?) {
            context ?: return
            if (BackgroundProcessing.hasActiveJobs()) return
            context.applicationContext.startService(
                Intent(context.applicationContext, VadProcessingService::class.java).apply {
                    action = ACTION_STOP
                }
            )
        }

        fun updateProgress(context: Context, text: String, progress: Int? = null) {
            if (!foregroundActive) return
            ProcessingNotifications.updateVad(context.applicationContext, text, progress)
        }
    }

    private var serviceWakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ProcessingNotifications.ensureChannels(this)
        acquireServiceWakeLock()
        promoteToForeground("正在后台分析语音 (VAD)…")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (BackgroundProcessing.hasActiveJobs()) {
                    promoteToForeground("正在后台分析语音 (VAD)…")
                    return START_STICKY
                }
                releaseServiceWakeLock()
                foregroundActive = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                acquireServiceWakeLock()
                promoteToForeground("正在后台分析语音 (VAD)…")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseServiceWakeLock()
        foregroundActive = false
        super.onDestroy()
    }

    private fun acquireServiceWakeLock() {
        if (serviceWakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        serviceWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NunaRecorder:VadFgService").apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.d(TAG, "Service WakeLock acquired")
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

    private fun promoteToForeground(content: String) {
        foregroundActive = true
        ProcessingWakeLock.ensureHeld(applicationContext)
        val notification = ProcessingNotifications.buildVad(this, content)
        ForegroundServiceHelper.startDataSync(
            this,
            ProcessingNotifications.VAD_NOTIFICATION_ID,
            notification
        )
    }
}
