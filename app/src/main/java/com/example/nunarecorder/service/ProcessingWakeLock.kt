package com.example.nunarecorder.service

import android.content.Context
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * 息屏后保持 CPU 运行。引用计数 + [ensureHeld] 供息屏广播补救。
 */
object ProcessingWakeLock {

    private const val TAG = "ProcessingWakeLock"
    private val holders = AtomicInteger(0)

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context) {
        val count = holders.incrementAndGet()
        if (count > 1) {
            Log.d(TAG, "WakeLock refcount=$count (already held)")
            return
        }
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NunaRecorder:BackgroundProcessing").apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.d(TAG, "WakeLock acquired")
    }

    @Synchronized
    fun ensureHeld(context: Context) {
        val wl = wakeLock
        if (wl != null && wl.isHeld) return
        if (holders.get() <= 0) {
            holders.set(1)
        }
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NunaRecorder:BackgroundProcessing").apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.d(TAG, "WakeLock re-acquired (ensureHeld)")
    }

    @Synchronized
    fun release() {
        val left = holders.decrementAndGet()
        if (left > 0) {
            Log.d(TAG, "WakeLock refcount=$left")
            return
        }
        holders.set(0)
        wakeLock?.let {
            if (it.isHeld) {
                try {
                    it.release()
                } catch (e: RuntimeException) {
                    Log.w(TAG, "WakeLock release: ${e.message}")
                }
            }
        }
        wakeLock = null
        Log.d(TAG, "WakeLock released")
    }
}
