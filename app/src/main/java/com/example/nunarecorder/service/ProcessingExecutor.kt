package com.example.nunarecorder.service

import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * 仅用于「格式迁移 / Opus 切分」，勿与 VAD 队列共用（单线程会被 runBlocking 消费循环占满）。
 */
object ProcessingExecutor {

    private const val TAG = "ProcessingExecutor"

    private val executor: ExecutorService = Executors.newSingleThreadExecutor(
        ThreadFactory { runnable ->
            Thread(runnable, "NunaMigration").apply { isDaemon = false }
        }
    )

    fun execute(block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "migration task failed", t)
            }
        }
    }
}
