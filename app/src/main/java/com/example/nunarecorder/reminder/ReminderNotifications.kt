package com.example.nunarecorder.reminder

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

/** 早晨提醒的通知渠道与内容。 */
object ReminderNotifications {

    const val CHANNEL_ID = "nuna_morning_reminder_v1"
    const val NOTIFICATION_ID = 1006

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "早上开始采集提醒",
                // DEFAULT 而不是 HIGH：这不是告警，是一句提示。
                // 用 HIGH 会横幅弹出打断他手上的事，那是最快换来"关通知"的做法。
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "采集日已经开始但还没开始录制时提醒一次" }
        )
    }

    fun build(context: Context): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 划掉也要留痕：否则分不出"提醒无效"和"他有意选择不录"
        val dismissed = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, MorningReminderReceiver::class.java)
                .setAction(MorningReminderReceiver.ACTION_DISMISSED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nuna)
            .setContentTitle("今天还没开始采集")
            .setContentText("戴上 Nuna 并点开始采集；不想录这一段就忽略这条")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    // 把"可以不理"写进去。他可能就是不想在早上那段录（隐私），
                    // 而一个听起来像命令的提醒会换来关通知，那就永久失效了。
                    "采集日从凌晨 4 点算起，现在还没有录到音频。\n" +
                        "戴上 Nuna，打开 App 点「开始采集」即可。\n" +
                        "如果你这段时间不想被录，忽略这条就行，今天不会再提醒第三次。"
                )
            )
            .setContentIntent(open)
            .setDeleteIntent(dismissed)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    fun cancel(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }
}
