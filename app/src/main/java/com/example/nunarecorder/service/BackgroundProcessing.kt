package com.example.nunarecorder.service

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger

/**
 * 统一跟踪后台处理任务（VAD / 迁移切分），协调 WakeLock 与 VAD 前台服务生命周期。
 */
object BackgroundProcessing {

    private val activeJobs = AtomicInteger(0)

    fun onJobScheduled(context: Context) {
        val n = activeJobs.incrementAndGet()
        if (n == 1) {
            ProcessingWakeLock.acquire(context)
        } else {
            ProcessingWakeLock.ensureHeld(context)
        }
        VadProcessingService.ensureRunning(context)
    }

    fun onJobFinished(context: Context) {
        val left = activeJobs.decrementAndGet()
        if (left > 0) return
        activeJobs.set(0)
        ProcessingWakeLock.release()
        VadProcessingService.stopIfIdle(context)
    }

    fun hasActiveJobs(): Boolean = activeJobs.get() > 0
}
