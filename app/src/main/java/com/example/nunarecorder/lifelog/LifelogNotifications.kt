package com.example.nunarecorder.lifelog

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.nunarecorder.MainActivity
import com.example.nunarecorder.R

object LifelogNotifications {
    const val CHANNEL_ID = "lifelog_annotations"
    const val EXTRA_OPEN_LIFELOG = "open_lifelog"
    private const val PREFS = "lifelog_notification_state"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "生活记录标注",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "活动变化和声音事件的可选确认"
            }
        )
    }

    fun showNewPrompts(context: Context, prompts: List<AnnotationPrompt>) {
        if (prompts.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val notified = prefs.getStringSet("notified_ids", emptySet()).orEmpty().toMutableSet()
        val fresh = prompts.filter { it.eventId.toString() !in notified }
        if (fresh.isEmpty()) return

        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_LIFELOG, true)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val first = fresh.first()
        val title = if (first.kind == "block") "活动似乎发生了变化" else "检测到一个声音事件"
        val text = if (fresh.size == 1) first.question else "${first.question}（另有 ${fresh.size - 1} 条）"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nuna)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        NotificationManagerCompat.from(context).notify(first.eventId.toInt(), notification)
        fresh.forEach { notified += it.eventId.toString() }
        prefs.edit().putStringSet(
            "notified_ids",
            notified.toList().takeLast(200).toSet()
        ).apply()
    }
}
