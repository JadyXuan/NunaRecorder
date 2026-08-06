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
        private const val CHANNEL_ID = "nuna_context_channel"
        private const val NOTIFICATION_ID = 1002

        const val ACTION_START = "com.example.nunarecorder.action.START_CONTEXT"
        const val ACTION_STOP  = "com.example.nunarecorder.action.STOP_CONTEXT"
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
        LocationUpdatesHelper.startUpdates(this, locationManager!!, listener)
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
                NotificationManager.IMPORTANCE_DEFAULT
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
