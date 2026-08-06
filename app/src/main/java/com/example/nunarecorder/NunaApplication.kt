package com.example.nunarecorder

import android.app.Application
import com.example.nunarecorder.data.UserSettingsStorage
import com.example.nunarecorder.diagnostics.DiagnosticLogger
import com.example.nunarecorder.lifelog.LifelogNotifications
import com.example.nunarecorder.lifelog.LifelogPollWorker
import com.example.nunarecorder.service.ScreenOffKeepAlive
import com.example.nunarecorder.sync.SessionAutoUploadWorker

class NunaApplication : Application() {
    lateinit var diagnosticLogger: DiagnosticLogger
        private set

    override fun onCreate() {
        super.onCreate()
        diagnosticLogger = DiagnosticLogger.create(this)
        diagnosticLogger.log(
            "app_process_started",
            mapOf(
                "version_name" to BuildConfig.VERSION_NAME,
                "version_code" to BuildConfig.VERSION_CODE,
                "sdk_int" to android.os.Build.VERSION.SDK_INT,
                "manufacturer" to android.os.Build.MANUFACTURER,
                "model" to android.os.Build.MODEL
            )
        )
        ScreenOffKeepAlive.register(this)
        LifelogNotifications.createChannel(this)
        val settings = UserSettingsStorage(this).load()
        LifelogPollWorker.schedule(
            this,
            enabled = settings.lifelogEnabled && settings.annotationPollingEnabled
        )
        SessionAutoUploadWorker.schedule(this, settings)
    }
}
