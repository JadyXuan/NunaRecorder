package com.example.nunarecorder.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.example.nunarecorder.MainActivity
import com.example.nunarecorder.R
import java.io.File
import java.io.FileOutputStream

/**
 * 前台服务：在录制期间持续采集 GPS + IMU 数据并写入 sidecar 文件。
 * 以前台服务方式运行，确保熄屏/后台情况下数据不中断。
 *
 * 启动方式：
 *   ContextDataService.start(context, audioFile)
 * 停止方式：
 *   ContextDataService.stop(context)
 */
class ContextDataService : Service() {

    companion object {
        private const val TAG = "ContextDataService"
        private const val CHANNEL_ID = "nuna_context_channel"
        private const val NOTIFICATION_ID = 1002

        const val ACTION_START = "com.example.nunarecorder.action.START_CONTEXT"
        const val ACTION_STOP  = "com.example.nunarecorder.action.STOP_CONTEXT"
        const val EXTRA_SIDECAR_PATH = "extra_sidecar_path"

        fun start(context: Context, audioFile: File) {
            val sidecar = File(
                audioFile.parentFile,
                "${audioFile.nameWithoutExtension}.bin"
            )
            val intent = Intent(context, ContextDataService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SIDECAR_PATH, sidecar.absolutePath)
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

    private var output: FileOutputStream? = null
    private val lock = Any()
    private var collecting = false

    private var sensorManager: SensorManager? = null
    private var locationManager: LocationManager? = null
    private var sensorListener: SensorEventListener? = null
    private var locationListener: LocationListener? = null

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
                startForeground(NOTIFICATION_ID, buildNotification("正在采集 GPS & IMU 数据..."))
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
            output = FileOutputStream(sidecar, true) // append，允许 Activity 已写入的 meta 行保留
            collecting = true
            Log.d(TAG, "Context capture started: ${sidecar.absolutePath}")

            startImu()
            startGps()
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

        listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD
        ).forEach { type ->
            sensorManager?.getDefaultSensor(type)?.also {
                sensorManager?.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        Log.d(TAG, "IMU listeners registered")
    }

    private fun startGps() {
        val hasFine = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        Log.d(TAG, "GPS permission: FINE=$hasFine COARSE=$hasCoarse")

        if (!hasFine && !hasCoarse) {
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

        // GPS provider（卫星定位）
        runCatching {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()
            )
            Log.d(TAG, "GPS_PROVIDER registered")
        }.onFailure { Log.w(TAG, "GPS_PROVIDER register failed", it) }

        // Network provider（Wi-Fi / 蜂窝辅助定位，可在室内补充）
        runCatching {
            locationManager?.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()
            )
            Log.d(TAG, "NETWORK_PROVIDER registered")
        }.onFailure { Log.w(TAG, "NETWORK_PROVIDER register failed", it) }
    }

    private fun stopCapture() {
        collecting = false

        runCatching { locationListener?.let { locationManager?.removeUpdates(it) } }
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
            ).apply { description = "采集 GPS 和 IMU 传感器数据" }
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
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Nuna 数据采集")
            .setContentText(content)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
