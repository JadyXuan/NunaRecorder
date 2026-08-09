package com.example.nunarecorder.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 在已确认具备位置权限后注册 GPS / NETWORK 定位更新。
 * 调用方须先调用 [hasLocationPermission]；本类内部方法标注 MissingPermission 仅供 Lint。
 */
object LocationUpdatesHelper {

    private const val TAG = "LocationUpdates"

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun hasFineLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * 一次注册的结果，**必须被记录下来**。
     *
     * 2026-08-09 全天数据：有传感器的会话 IMU 各 13 万–27 万条，**GPS 全是 0**；
     * 少数有 GPS 的会话里 `provider` 全是 `network`、精度 47 米，一个 `gps` 都没有。
     *
     * 成因是这个类原来把失败吞掉了：[hasLocationPermission] 只要 fine 或 coarse
     * 任一为真就放行，而只授予「大致位置」时 `GPS_PROVIDER` 的注册会抛
     * SecurityException，被 `runCatching` 接住、只打一行 warn——**数据里看不出
     * 这一路是断的，只看得到"没有 GPS 行"**，和"今天没出门"长得一模一样。
     *
     * 同意书 §3 承诺「每 30 秒记录一次 GPS」，这不是锦上添花。
     */
    data class Registration(
        val gpsRegistered: Boolean,
        val networkRegistered: Boolean,
        val hasFine: Boolean,
        val gpsProviderEnabled: Boolean,
        val failures: List<String>
    ) {
        val anyOk: Boolean get() = gpsRegistered || networkRegistered

        /** 写进 context.jsonl，让数据自己说明为什么没有 GPS 行 */
        fun toJsonLine(nowMs: Long): String =
            """{"type":"gps_status","t_ms":$nowMs,"gps_registered":$gpsRegistered,""" +
                """"network_registered":$networkRegistered,"has_fine_permission":$hasFine,""" +
                """"gps_provider_enabled":$gpsProviderEnabled,""" +
                """"failures":[${failures.joinToString(",") { "\"" + it.replace("\"", "'") + "\"" }}]}"""

        /** 给用户看的一句话；null = 没问题 */
        fun problem(): String? = when {
            !anyOk -> "定位完全没能注册，整段采集不会有任何 GPS。"
            !hasFine ->
                "只授予了「大致位置」，卫星定位用不了，只能拿到 Wi-Fi/基站定位" +
                    "（精度几十米，户外常常一个点都没有）。请到系统权限里改成「精确位置」。"
            !gpsProviderEnabled ->
                "系统里的 GPS 定位是关的（可能选了「省电模式/仅网络定位」）。" +
                    "请到系统定位设置里改成「高精度」。"
            else -> null
        }
    }

    /**
     * @return true 若至少成功注册一个 provider
     */
    fun startUpdates(
        context: Context,
        locationManager: LocationManager,
        listener: LocationListener,
        // 全天采集下 1 Hz 连续定位是电池杀手，而场景上下文并不需要秒级精度。
        // minDistance 必须是 0：LocationManager 要求 minTime 和 minDistance 同时满足，
        // 设成 10 米后静止不动的用户永远收不到定位——2026-07-31 首次实测四个会话
        // 的 context.jsonl 里 GPS 行数全部为 0，就是这么来的。
        minTimeMs: Long = 30_000L,
        minDistanceM: Float = 0f
    ): Boolean {
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "startUpdates: no location permission")
            return false
        }
        return registerProviders(locationManager, listener, minTimeMs, minDistanceM).anyOk
    }

    /** 同上，但把结果原样交回调用方去记录。新代码用这个。 */
    fun startUpdatesDetailed(
        context: Context,
        locationManager: LocationManager,
        listener: LocationListener,
        minTimeMs: Long = 30_000L,
        minDistanceM: Float = 0f
    ): Registration {
        val hasFine = hasFineLocation(context)
        val gpsEnabled = runCatching {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        if (!hasLocationPermission(context)) {
            return Registration(false, false, hasFine, gpsEnabled, listOf("no_location_permission"))
        }
        return registerProviders(locationManager, listener, minTimeMs, minDistanceM)
            .copy(hasFine = hasFine, gpsProviderEnabled = gpsEnabled)
    }

    fun stopUpdates(locationManager: LocationManager?, listener: LocationListener?) {
        if (locationManager == null || listener == null) return
        runCatching { locationManager.removeUpdates(listener) }
            .onFailure { Log.w(TAG, "removeUpdates failed", it) }
    }

    @SuppressLint("MissingPermission")
    private fun registerProviders(
        locationManager: LocationManager,
        listener: LocationListener,
        minTimeMs: Long,
        minDistanceM: Float
    ): Registration {
        var gpsOk = false
        var netOk = false
        val failures = mutableListOf<String>()
        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                minTimeMs,
                minDistanceM,
                listener,
                Looper.getMainLooper()
            )
            Log.d(TAG, "GPS_PROVIDER registered")
            gpsOk = true
        }.onFailure {
            Log.w(TAG, "GPS_PROVIDER register failed", it)
            failures.add("gps:${it.javaClass.simpleName}")
        }

        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                minTimeMs,
                minDistanceM,
                listener,
                Looper.getMainLooper()
            )
            Log.d(TAG, "NETWORK_PROVIDER registered")
            netOk = true
        }.onFailure {
            Log.w(TAG, "NETWORK_PROVIDER register failed", it)
            failures.add("network:${it.javaClass.simpleName}")
        }

        return Registration(gpsOk, netOk, hasFine = false, gpsProviderEnabled = false, failures = failures)
    }
}
