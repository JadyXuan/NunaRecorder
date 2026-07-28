package com.example.nunarecorder.lifelog

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.nunarecorder.data.UserSettingsStorage
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class LifelogPollWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = UserSettingsStorage(applicationContext).load()
        if (!settings.lifelogEnabled || !settings.annotationPollingEnabled) {
            return Result.success()
        }
        return try {
            val api = LifelogApiClient(
                OkHttpClient(),
                "http://${settings.serverHost}:${settings.serverPort}",
                settings.userId
            )
            val payload = api.pending()
            LifelogNotifications.showNewPrompts(applicationContext, payload.prompts)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK = "lifelog_annotation_poll"

        fun schedule(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (!enabled) {
                manager.cancelUniqueWork(UNIQUE_WORK)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<LifelogPollWorker>(
                15,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()
            manager.enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
