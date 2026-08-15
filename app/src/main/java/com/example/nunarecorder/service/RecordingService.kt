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
import com.example.nunarecorder.data.DeviceStorage
import com.example.nunarecorder.data.UserSettingsStorage
import com.example.nunarecorder.recording.LinkPhase
import com.example.nunarecorder.recording.RecordingController
import com.example.nunarecorder.recording.RecordingOptions
import com.example.nunarecorder.recording.CollectionClock
import com.example.nunarecorder.recording.RecordingStateMachine
import com.example.nunarecorder.recording.SessionRecorder
import com.example.nunarecorder.reminder.MorningReminderReceiver
import com.example.nunarecorder.session.LinkGapEntry
import com.example.nunarecorder.session.SessionPaths
import com.example.nunarecorder.util.DiagnosticsLog
import com.example.nunarecorder.util.PowerProbe
import com.example.nunarecorder.voiceprint.VoiceprintSession
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
        private const val LOW_BATTERY_NOTIFICATION_ID = 1003
        private const val OUTAGE_NOTIFICATION_ID = 1004
        /** 提醒用的独立渠道：只有它允许出声 */
        private const val ALERT_CHANNEL_ID = "nuna_alert_channel"
        /** 链路持续中断多久就主动提醒佩戴者 */
        private const val OUTAGE_ALERT_AFTER_MS = 5 * 60_000L
        /** 自我拉起闹钟的重排周期 */
        private const val WATCHDOG_ALARM_PERIOD_MS = 5 * 60_000L
        /** 从高到低；每个阈值只提醒一次 */
        private val LOW_BATTERY_THRESHOLDS = listOf(20, 10, 5)
        /** 功耗采样周期 */
        private const val POWER_SAMPLE_PERIOD_MS = 3 * 60_000L

        const val ACTION_START = "com.example.nunarecorder.action.START_RECORDING"
        /** 看门狗自我拉起。**与 ACTION_START 分开**，因为它必须先复核采集意图 */
        const val ACTION_WATCHDOG = "com.example.nunarecorder.action.WATCHDOG_RESTART"
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
    private var lastBatteryWarning = Int.MAX_VALUE
    private val powerProbe by lazy { PowerProbe(this) }
    private var lastPowerSampleMs = 0L
    /** 链路中断开始的时刻；0 = 没在中断 */
    private var outageStartedAtMs = 0L
    private var lastWatchdogArmMs = 0L
    /** 声纹录制导致的会话暂停起点；0 = 没在暂停 */
    @Volatile
    private var voiceprintPauseStartedAtMs = 0L
    private var outageAlerted = false
    /** 当前会话所属的小时格起点；跨过它就换会话 */
    private var currentHourStart = 0L
    private var recordingDeviceName = ""
    private var recordingDeviceAddress = ""
    private var recordingOptions: RecordingOptions? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            // 先看要不要换会话，再 tick：否则新的一小时头几秒会写进旧会话
            val nowMs = System.currentTimeMillis()
            if (currentHourStart > 0L && !CollectionClock.sameHour(currentHourStart, nowMs)) {
                rolloverSession()
            }
            recorder?.tick()
            val stats = recorder?.liveStats()
            RecordingController.publishStats(stats)
            // 功耗采样：分钟级，本身开销可忽略，但没有它就只能凭"感觉有点热"改代码
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastPowerSampleMs >= POWER_SAMPLE_PERIOD_MS) {
                lastPowerSampleMs = now
                powerProbe.sample(stats?.receivedPackets ?: 0L, stats?.totalBytes ?: 0L)
            }
            checkSustainedOutage(stats)
            armWatchdogAlarm()
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
                RecordingIntent.save(this, name, address)
                startRecording(name, address)
            }
            ACTION_WATCHDOG -> {
                // 看门狗到点。**只有采集意图还在时才恢复**——
                // 用户按过停止的话意图已经被清掉，这里必须什么都不做。
                val pending = RecordingIntent.load(this)
                if (pending == null) {
                    DiagnosticsLog.log(TAG, "看门狗到点，但用户已停止采集，不拉起")
                    if (!active) stopSelf()
                    return START_NOT_STICKY
                }
                if (active) return START_STICKY
                DiagnosticsLog.log(TAG, "看门狗发现采集已中断，恢复 device=${pending.deviceName}")
                startRecording(pending.deviceName, pending.deviceAddress)
            }
            ACTION_STOP -> {
                // 用户主动停止：清掉意图，否则系统重启服务时会自己又开始录
                RecordingIntent.clear(this)
                // 还要把已经排出去的闹钟撤掉。看门狗排的是 10 分钟之后的一次性唤醒，
                // 停止时不撤，它就会在十分钟内把采集重新拉起来——
                // 用户 2026-08-16 实测："怎么还会自动开启采集，毕竟有的时候用户主动关
                // 说明是有隐私消息"。**清意图是第二道闸，撤闹钟是第一道，两道都要有。**
                cancelSelfRestartAlarms()
                stopRecording()
                stopSelf()
            }
            else -> {
                // intent 为 null = 进程曾被系统杀死，START_STICKY 把服务拉了回来。
                //
                // 以前这里直接放弃，理由是"设备连接状态未知"。但实际后果是佩戴者
                // 过一阵子打开 App 发现采集停了，而中间那段时间全丢——对 16 小时
                // 佩戴来说这比"多一个会话"糟糕得多。
                //
                // 现在恢复，但**开一个新会话**而不是假装旧的还在继续：进程死过一次，
                // 中间缺了多久无从得知，用新会话 + 日志留痕如实表达，
                // 而不是把一段空白缝进旧会话的时间轴里。
                val intent2 = RecordingIntent.load(this)
                if (intent2 == null) {
                    DiagnosticsLog.log(TAG, "服务被系统重启，但没有待恢复的采集意图，退出")
                    stopSelf()
                    return START_NOT_STICKY
                }
                DiagnosticsLog.log(
                    TAG,
                    "服务被系统重启（进程曾被杀死），以新会话恢复采集 device=${intent2.deviceName}"
                )
                startRecording(intent2.deviceName, intent2.deviceAddress)
            }
        }
        return START_STICKY
    }

    /**
     * 佩戴者把任务卡片划掉。
     *
     * 前台服务本应活下来，但部分 OEM（vivo / 华为 / 小米）会连带杀掉进程。
     * 这里主动重排一次启动：如果进程真被杀了，`START_STICKY` 加上持久化的采集意图
     * 会把它拉回来；如果没被杀，这次重排是无害的（`startRecording` 对重复 START 是幂等的）。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (active) {
            DiagnosticsLog.log(TAG, "任务卡片被划掉，采集继续；已排重启兜底")
            val restart = Intent(this, RecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE_NAME, recordingDeviceName)
                putExtra(EXTRA_DEVICE_ADDRESS, recordingDeviceAddress)
            }
            val pi = PendingIntent.getForegroundService(
                this, 1, restart,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            runCatching {
                (getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
                    .set(
                        android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        android.os.SystemClock.elapsedRealtime() + 2_000L,
                        pi
                    )
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    /**
     * 周期性重排一次"把自己拉起来"的闹钟。
     *
     * 前台服务在 OEM 省电策略下照样会被杀，而 `START_STICKY` 不总是及时——
     * 2026-08-09 全天实测里采集停掉之后**恢复完全靠用户看手机**，空档最长 32 分钟
     * （T-2026-08-10-025）。这个负担不可能转移给 30 个外部参与者。
     *
     * 闹钟每 [WATCHDOG_ALARM_PERIOD_MS] 重排一次，永远指向"现在 + 2 倍周期"：
     * 服务活着就不停往后推，永远不会触发；服务死了，最后一次排的闹钟到点就把它拉回来。
     * `startRecording` 对重复 START 幂等，所以误触发无害。
     *
     * **`am force-stop` 之后这套不管用**——Android 会把应用置为 stopped 状态并取消
     * 它的闹钟，设计上就不允许自我拉起。那种杀法只有用户手动打开 App 才能恢复。
     * 现场真正会发生的是 OEM 低内存回收和划掉任务卡，这两种它都能兜。
     */
    private fun armWatchdogAlarm() {
        if (!active) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastWatchdogArmMs < WATCHDOG_ALARM_PERIOD_MS) return
        lastWatchdogArmMs = now
        val restart = Intent(this, RecordingService::class.java).apply {
            // **不能直接发 ACTION_START。** 那条闹钟是 10 分钟之后才响的，
            // 这中间用户完全可能按了停止——而按停止是一个隐私决定
            // （"这段我不想被录"），自己又拉起来等于推翻它。
            // 改成专用 action，响的时候再复核一次采集意图还在不在。
            action = ACTION_WATCHDOG
        }
        val pi = PendingIntent.getForegroundService(
            this, 2, restart,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            (getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
                .setExactAndAllowWhileIdle(
                    android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    now + WATCHDOG_ALARM_PERIOD_MS * 2,
                    pi
                )
        }
    }

    /**
     * 撤掉所有会把采集重新拉起来的闹钟。
     *
     * 两个来源：[onTaskRemoved] 排的一次性重启（请求码 1）和 [armWatchdogAlarm]
     * 排的看门狗（请求码 2）。**只在用户主动停止时调用**——
     * 服务被系统回收时不能撤，那正是 T-025 要靠它恢复的场景。
     */
    private fun cancelSelfRestartAlarms() {
        val am = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
        listOf(
            1 to Intent(this, RecordingService::class.java).apply { action = ACTION_START },
            2 to Intent(this, RecordingService::class.java).apply { action = ACTION_WATCHDOG }
        ).forEach { (code, intent) ->
            runCatching {
                PendingIntent.getForegroundService(
                    this, code, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ).also { am.cancel(it); it.cancel() }
            }
        }
        lastWatchdogArmMs = 0L
        DiagnosticsLog.log(TAG, "已撤销自我拉起闹钟（用户主动停止）")
    }

    override fun onDestroy() {
        // 不是用户按的停止。写成 user_stop 会把一次被杀记成正常收尾，
        // 而 manifest 是要进服务端被当成事实读的。
        stopRecording(com.example.nunarecorder.session.SessionManifest.END_SERVICE_DESTROYED)
        super.onDestroy()
    }

    // ── 录制 ───────────────────────────────────────────────────────────────

    private fun startRecording(deviceName: String, deviceAddress: String) {
        if (active) {
            DiagnosticsLog.log(TAG, "已在录制中，忽略重复 START")
            return
        }
        active = true
        lastBatteryWarning = Int.MAX_VALUE
        outageStartedAtMs = 0L
        outageAlerted = false
        powerProbe.reset()
        lastPowerSampleMs = android.os.SystemClock.elapsedRealtime()
        DiagnosticsLog.log(TAG, "开始录制 device=$deviceName addr=$deviceAddress")

        publish(stateMachine.onStartRequested(deviceName, deviceAddress))
        startForegroundCompat()
        acquireWakeLock()

        val options = RecordingOptions.from(UserSettingsStorage(this).load())
        recordingDeviceName = deviceName
        recordingDeviceAddress = deviceAddress
        // 已经开始录了就立刻停掉当天的早晨提醒。
        // 在你照做之后还响的通知，是最快让人去关通知的东西，而关掉就是永久失效。
        runCatching { MorningReminderReceiver.onRecordingStarted(this) }
        recordingOptions = options
        currentHourStart = CollectionClock.hourStart(System.currentTimeMillis())
        val sessionDir = SessionPaths.newSessionDir(deviceName, deviceAddress)
        val rec = newRecorder(options)
        recorder = rec
        rec.start(sessionDir, deviceName, deviceAddress, options)
        ContextDataService.start(this, sessionDir)

        link = NunaBleLink(applicationContext, linkListener).also { it.start(deviceAddress) }
        handler.post(ticker)
    }

    /**
     * 跨小时换会话。
     *
     * 一个 16 小时会话意味着**整天传完才算数**，中途任何一次失败的爆炸半径是一整天。
     * 切成小时之后就是 16 个各自可 commit 的单元，而且每个会话的 `context.jsonl`
     * 也从几百 MB 降到几十 MB。
     *
     * 边界对齐**本地整点**（服务端采集日规则是 `Asia/Hong_Kong@4`，见 CollectionClock）：
     * 两台手机在同一时刻采集，会话边界应当落在同一处，否则排障时无法横向对齐。
     * BLE 链路完全不受影响——只是换了写入目标。
     */
    private fun rolloverSession() {
        val options = recordingOptions ?: return
        val old = recorder ?: return
        val now = System.currentTimeMillis()
        DiagnosticsLog.log(
            TAG,
            "跨小时切换会话（采集日 ${CollectionClock.dayId(now)}），旧会话封口并可独立上传"
        )
        old.stop(com.example.nunarecorder.session.SessionManifest.END_ROLLOVER)

        currentHourStart = CollectionClock.hourStart(now)
        val dir = SessionPaths.newSessionDir(recordingDeviceName, recordingDeviceAddress)
        val rec = newRecorder(options)
        recorder = rec
        rec.start(dir, recordingDeviceName, recordingDeviceAddress, options)
        // **只切输出文件，不 stop 再 start。** 原来那样写是竞态：STOP 的 stopSelf()
        // 会在 START 之后才销毁服务，onDestroy 再 stopCapture 一次，把刚起来的采集掐掉；
        // 而 startForegroundService 之后服务被销毁、5 秒内没 startForeground，
        // Android 会直接杀进程。详见 ContextDataService.switchSession 与 T-2026-08-10-025。
        ContextDataService.switchSession(this, dir)
    }

    private fun newRecorder(options: RecordingOptions): SessionRecorder {
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
            },
            appVersion = appVersionLabel()
        )
        rec.diagnosticsSnapshot = { DiagnosticsLog.snapshot() }
        return rec
    }

    private fun stopRecording(
        endReason: String = com.example.nunarecorder.session.SessionManifest.END_USER_STOP
    ) {
        if (!active) return
        active = false
        DiagnosticsLog.log(TAG, "停止录制（$endReason）")

        handler.removeCallbacks(ticker)
        link?.release()
        link = null
        recorder?.stop(endReason)
        recorder = null
        currentHourStart = 0L
        recordingOptions = null
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
            // 声纹录制期间**只喂声纹，不写会话**。
            //
            // 用的是同一台设备的同一条流，所以麦克风匹配自动成立（这正是把声纹做进
            // App 的理由）；但那两段是入组材料，不该进当天的待标注数据——
            // 朗读文本和"讲讲你的一天"混进标注池，既污染数据集，也等于把一段
            // 明确为登记目的录的音拿去做别的用途。
            if (VoiceprintSession.isCapturing) {
                if (voiceprintPauseStartedAtMs == 0L) {
                    voiceprintPauseStartedAtMs = System.currentTimeMillis()
                }
                VoiceprintSession.feed(data)
                return
            }
            if (voiceprintPauseStartedAtMs != 0L) {
                // 空档要写进 manifest，否则事后看只是"这一分钟没音频"，和掉线没区别
                val from = voiceprintPauseStartedAtMs
                voiceprintPauseStartedAtMs = 0L
                handler.post {
                    recorder?.noteIntentionalGap(
                        from, System.currentTimeMillis(), LinkGapEntry.REASON_VOICEPRINT
                    )
                }
            }
            // BLE 回调线程直写，不绕主线程
            recorder?.feed(data)
        }

        override fun onSensorPacket(raw: ByteArray) {
            // BLE 线程直写，和音频同一条路径：毫米波包频率高，绕主线程只会添堵
            recorder?.feedSensorPacket(raw)
        }

        override fun onRadarState(enabled: Boolean) {
            handler.post { recorder?.onRadarState(enabled) }
        }

        override fun onBatteryLevel(percent: Int) {
            handler.post {
                publish(stateMachine.onBatteryLevel(percent))
                maybeWarnLowBattery(percent)
                updateNotification()
            }
        }

        override fun onFirmwareRevision(version: String, authoritative: Boolean) {
            handler.post {
                recorder?.setDeviceFirmware(version)
                // 只有权威来源值得存进设备记录：DIS 的 `1.0.0` 存进去等于把"未知"
                // 记成"已知且正常"，那条检查就永远放行。见 DeviceFirmwarePolicy。
                if (authoritative) {
                    recordingDeviceAddress?.let { addr ->
                        runCatching {
                            DeviceStorage(applicationContext).recordFirmware(addr, version)
                        }
                    }
                }
            }
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

    /**
     * 链路持续中断时主动提醒。
     *
     * 一整天佩戴，中途掉线而佩戴者没察觉 = 那天白采。常驻通知虽然写着状态，
     * 但人不会一直盯着它。**能被发现的故障远没有安静的故障可怕**，所以持续中断
     * 超过 [OUTAGE_ALERT_AFTER_MS] 就主动响一次。
     *
     * 只响一次，恢复后才重置：反复响会让人直接关掉通知，那就更糟了。
     * 走独立的提醒渠道——采集状态那个渠道是刻意静音的（每分钟一次的 VAD 提示音
     * 就是这么来的），但这一条值得打断。
     */
    private fun checkSustainedOutage(stats: com.example.nunarecorder.recording.LiveRecordingStats?) {
        val now = System.currentTimeMillis()
        val streaming = stateMachine.status.isStreaming &&
            stats?.lastFrameAtMs != null &&
            now - stats.lastFrameAtMs!! < 15_000L
        if (streaming) {
            if (outageAlerted) {
                DiagnosticsLog.log(TAG, "链路已恢复，撤销中断提醒")
                runCatching {
                    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                        .cancel(OUTAGE_NOTIFICATION_ID)
                }
            }
            outageStartedAtMs = 0L
            outageAlerted = false
            return
        }
        if (outageStartedAtMs == 0L) {
            outageStartedAtMs = now
            return
        }
        val downMs = now - outageStartedAtMs
        if (outageAlerted || downMs < OUTAGE_ALERT_AFTER_MS) return
        outageAlerted = true
        // 一帧都没收到过 vs 收到过又停了，是两种完全不同的处置
        val neverStreamed = stats?.lastFrameAtMs == null
        DiagnosticsLog.log(
            TAG,
            "链路已中断 ${downMs / 60000} 分钟，提醒佩戴者" +
                if (neverStreamed) "（本次会话从未出流，疑似还在充电板上）" else ""
        )
        runCatching {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(
                OUTAGE_NOTIFICATION_ID,
                androidx.core.app.NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_nuna)
                    .setContentTitle("已经 ${downMs / 60000} 分钟没有收到录音数据")
                    .setContentText(
                        if (neverStreamed) "设备可能还在充电板上——充电时它不采集。"
                        else "请检查 Nuna 设备是否还戴着、有没有电。这段时间的音频没有被采到。"
                    )
                    .setStyle(
                        androidx.core.app.NotificationCompat.BigTextStyle().bigText(
                            if (neverStreamed) {
                                // 用户 2026-08-09 指出：设备在充电时不采集，这是固件行为。
                                // 一次都没收到过数据，最可能就是它还在充电板上——
                                // 把这个原因排在最前面，而不是让人先去怀疑设备坏了。
                                "开始采集 ${downMs / 60000} 分钟了，一直没有收到数据。" +
                                    "设备很可能还在充电板上——充电时它不采集。" +
                                    "把它拿下来戴好，链路会自动恢复。"
                            } else {
                                "已经 ${downMs / 60000} 分钟没有收到录音数据。" +
                                    "请检查设备是否还戴在身上、是否还有电、是否离手机太远。" +
                                    "采集仍在自动重连，这段时间的音频没有被采到。"
                            }
                        )
                    )
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    /**
     * 设备低电量提醒。佩戴者不会主动去看电量，而设备没电等于当天剩下的时间全丢。
     * 每个阈值只提醒一次，避免在阈值附近反复弹。
     */
    private fun maybeWarnLowBattery(percent: Int) {
        val threshold = LOW_BATTERY_THRESHOLDS.firstOrNull { percent <= it } ?: return
        if (threshold >= lastBatteryWarning) return
        lastBatteryWarning = threshold
        DiagnosticsLog.log(TAG, "设备电量 $percent%，低于 $threshold% 阈值，提醒佩戴者充电")
        runCatching {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(
                LOW_BATTERY_NOTIFICATION_ID,
                androidx.core.app.NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_nuna)
                    .setContentTitle("录音设备电量 $percent%")
                    .setContentText("请尽快给 Nuna 设备充电，否则采集会中断")
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    /** `versionName (versionCode)`，例如 `1.1 (3)` */
    private fun appVersionLabel(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        "${info.versionName} ($code)"
    }.getOrDefault("unknown")

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Nuna 音频采集", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "录制状态与链路健康" }
        )
        // 独立的提醒渠道：常驻状态那个是刻意静音的，但"掉线五分钟"值得打断
        mgr.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID, "Nuna 采集异常提醒", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "链路长时间中断、设备低电量" }
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
                status.batteryPercent?.let { append(" · 设备 $it%") }
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
