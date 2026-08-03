package com.example.nunarecorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.example.nunarecorder.MainActivity
import com.example.nunarecorder.R
import com.example.nunarecorder.ble.NunaBleLink
import com.example.nunarecorder.data.UserSettingsStorage
import com.example.nunarecorder.recording.LinkPhase
import com.example.nunarecorder.recording.RecordingController
import com.example.nunarecorder.recording.RecordingOptions
import com.example.nunarecorder.recording.RecordingStateMachine
import com.example.nunarecorder.recording.SessionRecorder
import com.example.nunarecorder.session.SessionPaths
import com.example.nunarecorder.util.DiagnosticsLog
import com.example.nunarecorder.vad.VadJob
import com.example.nunarecorder.vad.VadJobQueue

/**
 * 采集前台服务：拥有 BLE 链路和会话录制，独立于任何 Activity。
 *
 * 之前采集管线挂在 `MainActivity` 的字段上，Activity 一没，采集就断。
 * 佩戴者报告"中间闪退过，重启两三次才能正常录制"就是这个后果。
 * 现在 Activity 只负责显示和下指令。
 *
 * 服务只在用户主动停止（[ACTION_STOP]）时结束；断连由 [NunaBleLink] 自行重连，
 * 会话保持打开并在 manifest 里留下中断区间。
 */
class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "nuna_recording_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TICK_PERIOD_MS = 1_000L

        const val ACTION_START = "com.example.nunarecorder.action.START_RECORDING"
        const val ACTION_STOP = "com.example.nunarecorder.action.STOP_RECORDING"
        const val EXTRA_DEVICE_NAME = "extra_device_name"
        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val stateMachine = RecordingStateMachine()
    private var link: NunaBleLink? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var recorder: SessionRecorder? = null
    private var active = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            recorder?.tick()
            RecordingController.publishStats(recorder?.liveStats())
            updateNotification()
            handler.postDelayed(this, TICK_PERIOD_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val name = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "unknown"
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                if (address.isNullOrBlank()) {
                    DiagnosticsLog.log(TAG, "START 缺少设备地址，忽略")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startRecording(name, address)
            }
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
            }
            else -> {
                // START_STICKY 重启时 intent 为 null：进程曾被杀死。
                // 不自动恢复采集——设备连接状态未知，静默续录会产出无法解释的数据。
                DiagnosticsLog.log(TAG, "服务被系统重启（intent=null），不自动恢复采集")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    // ── 录制 ───────────────────────────────────────────────────────────────

    private fun startRecording(deviceName: String, deviceAddress: String) {
        if (active) {
            DiagnosticsLog.log(TAG, "已在录制中，忽略重复 START")
            return
        }
        active = true
        DiagnosticsLog.log(TAG, "开始录制 device=$deviceName addr=$deviceAddress")

        publish(stateMachine.onStartRequested(deviceName, deviceAddress))
        startForegroundCompat()
        acquireWakeLock()

        val options = RecordingOptions.from(UserSettingsStorage(this).load())
        val sessionDir = SessionPaths.newSessionDir(deviceName, deviceAddress)
        val rec = SessionRecorder(
            onLog = { DiagnosticsLog.log("Recorder", it) },
            onSegmentClosed = { closed ->
                if (options.autoVadOnRecord) {
                    VadJobQueue.enqueue(
                        VadJob(
                            sessionDir = closed.sessionDir,
                            segmentIndex = closed.entry.index,
                            opusFile = closed.file,
                            audioRelPath = closed.entry.file,
                            startMs = closed.entry.startMs,
                            endMs = closed.entry.endMs,
                            durationMs = closed.entry.durationMs,
                            sessionStartedAtMs = closed.sessionStartedAtMs
                        )
                    )
                }
            }
        )
        recorder = rec
        rec.start(sessionDir, deviceName, deviceAddress, options)
        ContextDataService.start(this, sessionDir)

        link = NunaBleLink(applicationContext, linkListener).also { it.start(deviceAddress) }
        handler.post(ticker)
    }

    private fun stopRecording() {
        if (!active) return
        active = false
        DiagnosticsLog.log(TAG, "停止录制（用户主动）")

        handler.removeCallbacks(ticker)
        link?.release()
        link = null
        recorder?.stop()
        recorder = null
        ContextDataService.stop(this)
        releaseWakeLock()

        publish(stateMachine.onStopRequested())
        RecordingController.publishStats(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun publish(status: com.example.nunarecorder.recording.LinkStatus) {
        RecordingController.publishLink(status)
    }

    // ── BLE 回调 ───────────────────────────────────────────────────────────

    private val linkListener = object : NunaBleLink.Listener {
        override fun onLinkLog(message: String) = Unit

        override fun onGattConnected(deviceName: String) {
            handler.post { publish(stateMachine.onGattConnected()) }
        }

        override fun onAudioSubscribed() {
            handler.post {
                // 首次订阅不算"恢复"；只有确实中断过才结束一段 link gap
                recorder?.onLinkRestored()
                publish(stateMachine.onAudioSubscribed())
                updateNotification()
            }
        }

        override fun onDisconnected(reason: String) {
            handler.post {
                recorder?.onLinkLost(reason)
                publish(stateMachine.onDisconnected(reason))
                updateNotification()
            }
        }

        override fun onReconnectScheduled(attempt: Int, delayMs: Long) {
            handler.post {
                recorder?.onReconnectAttempt()
                publish(stateMachine.onReconnectScheduled(attempt, delayMs))
                updateNotification()
            }
        }

        override fun onReconnecting() {
            handler.post { publish(stateMachine.onReconnecting()) }
        }

        override fun onAudioData(data: ByteArray) {
            // BLE 回调线程直写，不绕主线程
            recorder?.feed(data)
        }

        override fun onFatal(reason: String) {
            handler.post {
                DiagnosticsLog.log(TAG, "致命错误，停止录制：$reason")
                stopRecording()
                stopSelf()
            }
        }
    }

    // ── 前台 / 唤醒 ────────────────────────────────────────────────────────

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 16 小时连续采集：BLE 回调虽然会唤醒 CPU，但 Doze 下的定时器会被推迟，
     * 分段轮转和 manifest 落盘会跟着漂。研究采集场景下这点耗电是可接受成本。
     */
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nuna:recording").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Nuna 音频采集", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "录制状态与链路健康" }
        )
    }

    /** 通知栏是佩戴者唯一常驻的反馈；必须写链路是否真的在收数据，而不是只写"录制中"。 */
    private fun buildNotification(): Notification {
        val status = stateMachine.status
        val stats = recorder?.liveStats()
        val title = when (status.phase) {
            LinkPhase.RECORDING -> "正在采集"
            LinkPhase.RECONNECTING -> "链路中断，正在重连"
            LinkPhase.CONNECTING -> "正在连接设备"
            LinkPhase.CONNECTED -> "已连接，正在握手"
            LinkPhase.IDLE -> "已停止"
        }
        val detail = buildString {
            if (status.phase == LinkPhase.RECONNECTING) {
                append("第 ${status.reconnectAttempt} 次重试")
                status.reason?.let { append(" · $it") }
            } else if (stats != null) {
                append("${stats.closedSegmentCount} 段")
                if (stats.expectedPackets > 0L) {
                    append(" · 完整度 %.0f%%".format(stats.completeness * 100))
                }
                if (stats.disconnectCount > 0) append(" · 断连 ${stats.disconnectCount} 次")
            }
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nuna)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        if (!active) return
        runCatching {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIFICATION_ID, buildNotification())
        }
    }
}
