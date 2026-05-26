package com.example.nunarecorder

import android.app.Application
import com.example.nunarecorder.service.ScreenOffKeepAlive

class NunaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ScreenOffKeepAlive.register(this)
    }
}
