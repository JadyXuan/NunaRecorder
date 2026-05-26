package com.example.nunarecorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.nunarecorder.MainActivity
import com.example.nunarecorder.R

/**
 * 后台处理（VAD / 迁移）通知：使用 DEFAULT 重要性，状态栏可见。
 */
object ProcessingNotifications {

    const val VAD_CHANNEL_ID = "nuna_processing_vad"
    const val MIGRATION_CHANNEL_ID = "nuna_processing_migration"
    const val VAD_NOTIFICATION_ID = 1003
    const val MIGRATION_NOTIFICATION_ID = 1004

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(
                VAD_CHANNEL_ID,
                "VAD 语音分析",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "后台 Silero VAD 预标注进度"
                setShowBadge(true)
            }
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                MIGRATION_CHANNEL_ID,
                "录音格式迁移",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "后台将旧版录音转为分段格式"
                setShowBadge(true)
            }
        )
    }

    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val mgr = context.getSystemService(NotificationManager::class.java)
        return mgr.areNotificationsEnabled()
    }

    fun buildVad(
        context: Context,
        content: String,
        progress: Int? = null,
        progressMax: Int = 100
    ): Notification = build(
        context,
        VAD_CHANNEL_ID,
        "Nuna · VAD 分析",
        content,
        progress,
        progressMax
    )

    fun buildMigration(
        context: Context,
        content: String,
        progress: Int = 0,
        indeterminate: Boolean = false
    ): Notification = build(
        context,
        MIGRATION_CHANNEL_ID,
        "Nuna · 格式迁移",
        content,
        if (indeterminate) null else progress,
        100
    )

    fun updateVad(context: Context, content: String, progress: Int? = null) {
        ensureChannels(context)
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.notify(VAD_NOTIFICATION_ID, buildVad(context, content, progress))
    }

    fun updateMigration(context: Context, content: String, progress: Int, indeterminate: Boolean = false) {
        ensureChannels(context)
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.notify(
            MIGRATION_NOTIFICATION_ID,
            buildMigration(context, content, progress, indeterminate)
        )
    }

    private fun build(
        context: Context,
        channelId: String,
        title: String,
        content: String,
        progress: Int?,
        progressMax: Int
    ): Notification {
        val pi = PendingIntent.getActivity(
            context,
            channelId.hashCode(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_nuna)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (progress != null) {
            builder.setProgress(progressMax, progress.coerceIn(0, progressMax), false)
        }
        return builder.build()
    }
}
