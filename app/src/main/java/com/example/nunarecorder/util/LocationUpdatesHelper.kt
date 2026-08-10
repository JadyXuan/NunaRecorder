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
        val failures: List<String>,
        /** Play 服务的融合定位是否注册上；它才是户外真正拿得到卫星定位的那一路 */
        val fusedRegistered: Boolean = false
    ) {
        val anyOk: Boolean get() = fusedRegistered || gpsRegistered || networkRegistered

        /** 写进 context.jsonl，让数据自己说明为什么没有 GPS 行 */
        fun toJsonLine(nowMs: Long): String =
            """{"type":"gps_status","t_ms":$nowMs,"fused_registered":$fusedRegistered,""" +
                """"gps_registered":$gpsRegistered,""" +
                """"network_registered":$networkRegistered,"has_fine_permission":$hasFine,""" +
                """"gps_provider_enabled":$gpsProviderEnabled,""" +
                """"failures":[${failures.joinToString(",") { "\"" + it.replace("\"", "'") + "\"" }}]}"""

        /** 给用户看的一句话；null = 没问题 */
        fun problem(): String? = when {
            // 顺序 = 从最明确、最可操作的原因往下排。
            // 权限和系统设置是用户点两下就能解决的；"只剩网络定位"是兜底描述，
            // 只有在前两条都排除之后才说，否则会把人引到错的地方去。
            !anyOk -> "定位完全没能注册，整段采集不会有任何 GPS。"
            !hasFine ->
                "只授予了「大致位置」，卫星定位用不了，只能拿到 Wi-Fi/基站定位" +
                    "（精度几十米，户外常常一个点都没有）。请到系统权限里改成「精确位置」。"
            !gpsProviderEnabled ->
                "系统里的 GPS 定位是关的（可能选了「省电模式/仅网络定位」）。" +
                    "请到系统定位设置里改成「高精度」。"
            !fusedRegistered && !gpsRegistered ->
                "权限和系统设置都正常，但只注册上了 Wi-Fi/基站定位——" +
                    "拿不到卫星定位，户外常常一个点都没有。请把这条反馈给我们。"
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

    /**
     * 融合定位（Play 服务）。**这才是户外真正拿得到卫星定位的那一路。**
     *
     * 2026-08-09 全天只采到 `network` 定位，而环境自检**本来就区分 fine 和 coarse**——
     * 也就是说光有"权限判据太松"解释不了全部：裸 `LocationManager.GPS_PROVIDER`
     * 在前台服务里依然可能长时间不出点（系统定位模式选了省电、OEM 后台策略、
     * 或者单纯拿不到星）。融合定位把 provider 选择交给系统，并且是 Play 服务
     * 唯一会在后台稳定投递的路径。
     *
     * 仍然保留 LocationManager 作为兜底：不是每台手机都有 Play 服务
     * （T-2026-08-07-018 里 GMS 可用性正是要上报的字段之一）。
     *
     * @return 是否注册成功
     */
    @SuppressLint("MissingPermission")
    private fun startFused(
        context: Context,
        minTimeMs: Long,
        onLocation: (android.location.Location) -> Unit,
        failures: MutableList<String>
    ): com.google.android.gms.location.FusedLocationProviderClient? {
        if (!hasFineLocation(context)) {
            failures.add("fused:no_fine_permission")
            return null
        }
        val available = com.google.android.gms.common.GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context)
        if (available != com.google.android.gms.common.ConnectionResult.SUCCESS) {
            failures.add("fused:play_services_$available")
            return null
        }
        return runCatching {
            val client = com.google.android.gms.location.LocationServices
                .getFusedLocationProviderClient(context)
            val request = com.google.android.gms.location.LocationRequest.Builder(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                minTimeMs
            )
                // 同意书 §3 承诺"每 30 秒记录一次"，所以下限也压在 30 秒附近，
                // 不要让系统为了省电把间隔拉长到几分钟。
                .setMinUpdateIntervalMillis(minTimeMs)
                .setWaitForAccurateLocation(false)
                .build()
            val cb = object : com.google.android.gms.location.LocationCallback() {
                override fun onLocationResult(
                    result: com.google.android.gms.location.LocationResult
                ) {
                    result.locations.forEach(onLocation)
                }
            }
            fusedCallback = cb
            client.requestLocationUpdates(request, cb, Looper.getMainLooper())
            Log.d(TAG, "FusedLocationProvider registered (interval=${minTimeMs}ms)")
            client
        }.onFailure {
            Log.w(TAG, "Fused register failed", it)
            failures.add("fused:${it.javaClass.simpleName}")
        }.getOrNull()
    }

    private var fusedClient: com.google.android.gms.location.FusedLocationProviderClient? = null
    private var fusedCallback: com.google.android.gms.location.LocationCallback? = null

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
        val failures = mutableListOf<String>()
        // 融合定位优先。它拿不到（没有 Play 服务、没有精确权限）时才退回裸 provider。
        fusedClient = startFused(context, minTimeMs, { listener.onLocationChanged(it) }, failures)
        val lm = registerProviders(locationManager, listener, minTimeMs, minDistanceM)
        return lm.copy(
            hasFine = hasFine,
            gpsProviderEnabled = gpsEnabled,
            fusedRegistered = fusedClient != null,
            failures = lm.failures + failures
        )
    }

    fun stopUpdates(locationManager: LocationManager?, listener: LocationListener?) {
        fusedCallback?.let { cb ->
            runCatching { fusedClient?.removeLocationUpdates(cb) }
                .onFailure { Log.w(TAG, "fused removeUpdates failed", it) }
        }
        fusedCallback = null
        fusedClient = null
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
