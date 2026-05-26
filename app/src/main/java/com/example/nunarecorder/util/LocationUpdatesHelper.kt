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

    /**
     * @return true 若至少成功注册一个 provider
     */
    fun startUpdates(
        context: Context,
        locationManager: LocationManager,
        listener: LocationListener,
        minTimeMs: Long = 1000L,
        minDistanceM: Float = 0f
    ): Boolean {
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "startUpdates: no location permission")
            return false
        }
        return registerProviders(locationManager, listener, minTimeMs, minDistanceM)
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
    ): Boolean {
        var anyOk = false
        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                minTimeMs,
                minDistanceM,
                listener,
                Looper.getMainLooper()
            )
            Log.d(TAG, "GPS_PROVIDER registered")
            anyOk = true
        }.onFailure { Log.w(TAG, "GPS_PROVIDER register failed", it) }

        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                minTimeMs,
                minDistanceM,
                listener,
                Looper.getMainLooper()
            )
            Log.d(TAG, "NETWORK_PROVIDER registered")
            anyOk = true
        }.onFailure { Log.w(TAG, "NETWORK_PROVIDER register failed", it) }

        return anyOk
    }
}
