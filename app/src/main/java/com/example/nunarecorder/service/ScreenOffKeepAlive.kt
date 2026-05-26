package com.example.nunarecorder.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 息屏时部分机型会冻结应用；若有后台任务则重新持有 WakeLock 并拉起 VAD 前台服务。
 */
object ScreenOffKeepAlive {

    private const val TAG = "ScreenOffKeepAlive"

    @Volatile
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_DREAMING_STARTED -> onScreenOff(context.applicationContext)
            }
        }
    }

    fun register(context: Context) {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_DREAMING_STARTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.applicationContext.registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            ContextCompat.registerReceiver(
                context.applicationContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        registered = true
        Log.d(TAG, "Screen-off keep-alive registered")
    }

    private fun onScreenOff(appContext: Context) {
        if (!BackgroundProcessing.hasActiveJobs()) return
        Log.d(TAG, "Screen off with active jobs — re-hold wake lock & FGS")
        ProcessingWakeLock.ensureHeld(appContext)
        VadProcessingService.ensureRunning(appContext)
    }
}
