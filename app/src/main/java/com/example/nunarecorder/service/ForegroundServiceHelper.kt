package com.example.nunarecorder.service

import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat

object ForegroundServiceHelper {

    const val CONNECTED_DEVICE_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    const val LOCATION_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    const val DATA_SYNC_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

    fun startConnectedDevice(service: Service, notificationId: Int, notification: Notification) {
        start(service, notificationId, notification, CONNECTED_DEVICE_TYPE)
    }

    fun startLocation(service: Service, notificationId: Int, notification: Notification) {
        start(service, notificationId, notification, LOCATION_TYPE)
    }

    fun startDataSync(service: Service, notificationId: Int, notification: Notification) {
        start(service, notificationId, notification, DATA_SYNC_TYPE)
    }

    private fun start(
        service: Service,
        notificationId: Int,
        notification: Notification,
        foregroundServiceType: Int
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                service,
                notificationId,
                notification,
                foregroundServiceType
            )
        } else {
            service.startForeground(notificationId, notification)
        }
    }
}
