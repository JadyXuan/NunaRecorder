package com.example.nunarecorder

import android.app.Application
import com.example.nunarecorder.data.UserSettingsStorage
import com.example.nunarecorder.lifelog.LifelogNotifications
import com.example.nunarecorder.lifelog.LifelogPollWorker
import com.example.nunarecorder.service.ScreenOffKeepAlive

class NunaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ScreenOffKeepAlive.register(this)
        LifelogNotifications.createChannel(this)
        val settings = UserSettingsStorage(this).load()
        LifelogPollWorker.schedule(
            this,
            enabled = settings.lifelogEnabled && settings.annotationPollingEnabled
        )
    }
}
