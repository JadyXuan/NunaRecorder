package com.example.nunarecorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.nunarecorder.MainActivity
import com.example.nunarecorder.R
import com.example.nunarecorder.context.ActivityRecognitionCollector
import com.example.nunarecorder.util.LocationUpdatesHelper
import java.io.File
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * 前台服务：在录制期间持续采集 GPS + IMU + 身体活动，写入会话目录 `context/context.jsonl`。
 * 以前台服务方式运行，确保熄屏/后台情况下数据不中断。
 *
 * 启动：[start]；停止：[stop]。
 *
 * Schema: [docs/SESSION_SYNC_PROTOCOL.md] (section 1.2).
 */
class ContextDataService : Service() {

    companion object {
        private const val TAG = "ContextDataService"

        /** 25 Hz：HAR 够用，且把 16 小时的 context.jsonl 从 ~830 MB 压到 ~276 MB */
        private const val IMU_PERIOD_US = 40_000
        /** 5 Hz：磁力计对 wearer activity 贡献很小，没必要按 IMU 频率采 */
        private const val MAG_PERIOD_US = 200_000
        /** 缓冲落盘间隔；崩溃最多丢这么久的 context 行 */
        private const val FLUSH_PERIOD_MS = 5_000L
        private const val CHANNEL_ID = "nuna_context_channel_v2"
        private const val NOTIFICATION_ID = 1002

        const val ACTION_START = "com.example.nunarecorder.action.START_CONTEXT"
        const val ACTION_STOP  = "com.example.nunarecorder.action.STOP_CONTEXT"
        /** 整点轮转：只换输出文件，不停服务、不动传感器注册 */
        const val ACTION_SWITCH = "com.example.nunarecorder.action.SWITCH_CONTEXT"
        const val EXTRA_SIDECAR_PATH = "extra_sidecar_path"

        /** 新格式：写入会话目录下 context/context.jsonl */
        fun start(context: Context, sessionDir: File) {
            val contextFile = com.example.nunarecorder.session.SessionPaths.contextFile(sessionDir)
            val intent = Intent(context, ContextDataService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SIDECAR_PATH, contextFile.absolutePath)
            }
            context.startForegroundService(intent)
        }

        /**
         * 整点换会话时**只切输出文件**，绝不 stop 再 start。
         *
         * 2026-08-09 全天实测（T-2026-08-10-025）：`rolloverSession()` 原来是
         * `stop()` 紧接 `start()`，两条命令投给同一个服务，于是
         *
         * - ACTION_STOP 的 `stopSelf()` 排在 ACTION_START **之后**才真正销毁服务，
         *   `onDestroy()` 又调一次 `stopCapture()`，**把刚起来的采集掐掉**——
         *   整点轮转出来的会话 `context.jsonl` 只有 2–7 条（meta 行加几个 IMU 采样）
         *   然后再无数据。全天 813 段里 452 段（55.6%）完全没有 IMU/GPS/activity，
         *   而 manifest 里 `modalities` 照样写着三个模态：静默失败。
         * - 更糟的是 `startForegroundService()` 之后服务若被销毁、5 秒内没有
         *   `startForeground()`，Android 会抛 ForegroundServiceDidNotStartInTime
         *   把进程杀掉——17 个整点里 6 次崩掉采集就是它，偶尔能自愈是因为这是竞态。
         *
         * 换文件不碰传感器注册还有一个好处：GPS provider 不用每小时重新预热一次。
         */
        fun switchSession(context: Context, sessionDir: File) {
            val contextFile = com.example.nunarecorder.session.SessionPaths.contextFile(sessionDir)
            context.startService(
                Intent(context, ContextDataService::class.java).apply {
                    action = ACTION_SWITCH
                    putExtra(EXTRA_SIDECAR_PATH, contextFile.absolutePath)
                }
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ContextDataService::class.java).apply {
                    action = ACTION_STOP
                }
            )
        }
    }

    /**
     * 带缓冲。原来是裸 [FileOutputStream]，25 Hz 加速度 + 25 Hz 陀螺 + 5 Hz 磁力计
     * ≈ 55 次 write() syscall/秒，连续 16 小时。缓冲后按 [FLUSH_PERIOD_MS] 落盘，
     * 崩溃最多丢这么久的 context —— 音频不受影响。
     */
    private var output: OutputStream? = null
    private var lastFlushMs = 0L
    private val lock = Any()
    private var collecting = false

    private var sensorManager: SensorManager? = null
    private var locationManager: LocationManager? = null
    private var sensorListener: SensorEventListener? = null
    private var locationListener: LocationListener? = null
    private var activityCollector: ActivityRecognitionCollector? = null
    /** 最近一次定位注册结论，整点轮转时补写进新会话 */
    private var lastGpsRegistration: LocationUpdatesHelper.Registration? = null

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val path = intent.getStringExtra(EXTRA_SIDECAR_PATH) ?: run {
                    stopSelf(); return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification("正在采集 GPS、IMU 与身体活动..."))
                startCapture(File(path))
            }
            ACTION_SWITCH -> {
                val path = intent.getStringExtra(EXTRA_SIDECAR_PATH)
                if (path == null) {
                    Log.w(TAG, "SWITCH 缺少目标路径，保持原样")
                } else if (!collecting) {
                    // 服务不知怎么已经停了：当成一次正常启动，别让新会话没有传感器数据
                    Log.w(TAG, "SWITCH 时采集未在进行，按 START 处理")
                    startForeground(NOTIFICATION_ID, buildNotification("正在采集 GPS、IMU 与身体活动..."))
                    startCapture(File(path))
                } else {
                    switchOutput(File(path))
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun startCapture(sidecar: File) {
        stopCapture()
        try {
            output = BufferedOutputStream(FileOutputStream(sidecar, true), 64 * 1024)
            lastFlushMs = System.currentTimeMillis()
            collecting = true
            Log.d(TAG, "Context capture started: ${sidecar.absolutePath}")

            writeRecord(
                """{"type":"meta","started_at_ms":${System.currentTimeMillis()},"context_file":"${sidecar.name}","modalities":["imu","gps","activity"]}"""
            )
            startImu()
            startGps()
            startActivity()
        } catch (e: Exception) {
            Log.e(TAG, "startCapture failed", e)
            stopSelf()
        }
    }

    private fun startImu() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!collecting) return
                val type = when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER  -> "accel"
                    Sensor.TYPE_GYROSCOPE      -> "gyro"
                    Sensor.TYPE_MAGNETIC_FIELD -> "mag"
                    else -> return
                }
                val x = event.values.getOrNull(0) ?: 0f
                val y = event.values.getOrNull(1) ?: 0f
                val z = event.values.getOrNull(2) ?: 0f
                writeRecord(
                    """{"type":"imu","sensor":"$type","t_ms":${System.currentTimeMillis()},"x":$x,"y":$y,"z":$z}"""
                )
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorListener = listener

        // 采样率按全天采集选择，不是按传感器能力拉满。
        // SENSOR_DELAY_GAME 约 50 Hz，三个传感器合计 150 行/秒；一个 16 小时的
        // 佩戴日会写出约 830 MB 的 context.jsonl，是音频本身的 3.6 倍，而且是
        // 单个文件一次性上传。HAR 常用 20–50 Hz，25 Hz 已经足够；磁力计对
        // wearer activity 贡献很小，降到 5 Hz。
        listOf(
            Sensor.TYPE_ACCELEROMETER to IMU_PERIOD_US,
            Sensor.TYPE_GYROSCOPE to IMU_PERIOD_US,
            Sensor.TYPE_MAGNETIC_FIELD to MAG_PERIOD_US
        ).forEach { (type, periodUs) ->
            sensorManager?.getDefaultSensor(type)?.also {
                sensorManager?.registerListener(listener, it, periodUs)
            }
        }
        Log.d(TAG, "IMU listeners registered")
    }

    private fun startGps() {
        if (!LocationUpdatesHelper.hasLocationPermission(this)) {
            Log.w(TAG, "No location permission — GPS capture skipped")
            return
        }

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val listener = LocationListener { location: Location ->
            if (!collecting) return@LocationListener
            writeRecord(
                """{"type":"gps","provider":"${location.provider}","t_ms":${System.currentTimeMillis()},"lat":${location.latitude},"lon":${location.longitude},"alt":${location.altitude},"acc":${location.accuracy},"speed":${location.speed},"bearing":${location.bearing}}"""
            )
            Log.d(TAG, "GPS fix: ${location.provider} lat=${location.latitude} lon=${location.longitude} acc=${location.accuracy}m")
        }
        locationListener = listener
        val reg = LocationUpdatesHelper.startUpdatesDetailed(this, locationManager!!, listener)
        lastGpsRegistration = reg
        // 把注册结果写进 context.jsonl。没有这一行，"这段没有 GPS" 和 "今天没出门"
        // 在数据里长得一模一样——2026-08-09 全天 GPS 几乎为零就卡在这里查不下去。
        writeRecord(reg.toJsonLine(System.currentTimeMillis()))
        reg.problem()?.let {
            Log.w(TAG, "GPS 不可用：$it")
            com.example.nunarecorder.util.DiagnosticsLog.log(TAG, "GPS 不可用：$it")
        }
    }

    private fun startActivity() {
        activityCollector = ActivityRecognitionCollector(this) { state, confidence, tMs ->
            writeRecord(
                """{"type":"activity","state":"$state","confidence":$confidence,"t_ms":$tMs}"""
            )
        }
        if (!activityCollector!!.start()) {
            Log.w(TAG, "Activity recognition unavailable (GMS or permission)")
            activityCollector = null
        }
    }

    /**
     * 只换输出文件：旧文件 flush + close，新文件接着写，**传感器注册原样保留**。
     *
     * 加锁是必须的——IMU 回调在传感器线程上写 [output]，这里在主线程换它。
     */
    private fun switchOutput(sidecar: File) {
        synchronized(lock) {
            runCatching { output?.flush(); output?.close() }
            output = try {
                BufferedOutputStream(FileOutputStream(sidecar, true), 64 * 1024)
            } catch (e: Exception) {
                Log.e(TAG, "切换 context 输出失败: ${e.message}")
                collecting = false
                null
            }
            lastFlushMs = System.currentTimeMillis()
        }
        if (output == null) return
        Log.d(TAG, "Context 输出已切到 ${sidecar.absolutePath}")
        // 整点轮转不重新注册 provider（那正是 T-025 的修法），所以要把上一次的
        // 注册结论补写进新文件。否则轮转出来的会话没有 gps_status 行——
        // 2026-08-10 实测 15:00 和 16:00 两个会话就缺这一行，而它是唯一能分辨
        // "这段没有 GPS" 和 "这段 GPS 那一路是断的" 的依据。
        lastGpsRegistration?.let { writeRecord(it.toJsonLine(System.currentTimeMillis())) }
        writeRecord(
            """{"type":"meta","started_at_ms":${System.currentTimeMillis()},"context_file":"${sidecar.name}","modalities":["imu","gps","activity"],"reason":"hourly_rollover"}"""
        )
    }

    private fun stopCapture() {
        collecting = false

        activityCollector?.stop()
        activityCollector = null

        LocationUpdatesHelper.stopUpdates(locationManager, locationListener)
        locationListener = null
        locationManager = null

        runCatching { sensorListener?.let { sensorManager?.unregisterListener(it) } }
        sensorListener = null
        sensorManager = null

        synchronized(lock) {
            runCatching { output?.flush(); output?.close() }
            output = null
        }
        Log.d(TAG, "Context capture stopped")
    }

    private fun writeRecord(line: String) {
        synchronized(lock) {
            if (!collecting) return
            runCatching {
                output?.write((line + "\n").toByteArray(Charsets.UTF_8))
                val now = System.currentTimeMillis()
                if (now - lastFlushMs >= FLUSH_PERIOD_MS) {
                    output?.flush()
                    lastFlushMs = now
                }
            }
        }
    }

    // ─── 通知 ──────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Nuna 上下文采集",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "采集 GPS、IMU 与身体活动状态" }
            mgr.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(content: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nuna)
            .setContentTitle("Nuna 数据采集")
            .setContentText(content)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
