package com.example.nunarecorder.reminder

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/**
 * [MorningReminder.Progress] 的落盘。
 *
 * 必须持久化：提醒发生在**没有会话**的时候，进程随时可能被回收，
 * 而"今天发过几次"如果只存在内存里，重启一次就会从头再发一遍——
 * 那正好破坏 [MorningReminder.MAX_PER_DAY] 这条"能不能长期活着"的关键约束。
 */
class MorningReminderStore(context: Context) {

    private val app = context.applicationContext
    private val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): MorningReminder.Progress = MorningReminder.Progress(
        dayId = sp.getString(K_DAY, "") ?: "",
        sentCount = sp.getInt(K_SENT, 0),
        dismissedCount = sp.getInt(K_DISMISSED, 0),
        unlockEpisodes = sp.getInt(K_EPISODES, 0),
        lastSentEpisode = sp.getInt(K_LAST_SENT_EP, -1),
        wasInUse = sp.getBoolean(K_WAS_IN_USE, false),
        firstInUseAtMs = sp.getLong(K_FIRST_USE, 0L)
    )

    fun save(p: MorningReminder.Progress) {
        sp.edit()
            .putString(K_DAY, p.dayId)
            .putInt(K_SENT, p.sentCount)
            .putInt(K_DISMISSED, p.dismissedCount)
            .putInt(K_EPISODES, p.unlockEpisodes)
            .putInt(K_LAST_SENT_EP, p.lastSentEpisode)
            .putBoolean(K_WAS_IN_USE, p.wasInUse)
            .putLong(K_FIRST_USE, p.firstInUseAtMs)
            .apply()
    }

    /** 参与者把通知划掉了。**这是"提醒无效"和"他有意不录"的唯一区分依据。** */
    fun recordDismissed() {
        sp.edit().putInt(K_DISMISSED, sp.getInt(K_DISMISSED, 0) + 1).apply()
    }

    /**
     * 通知这一路现在还通不通。
     *
     * 同时看两层：应用级总开关，和这个渠道自己有没有被单独关掉——
     * **只看前者会漏掉"只把这个渠道静音了"，而那同样是永久失效。**
     */
    fun notificationsEnabled(): Boolean = runCatching {
        val mgr = NotificationManagerCompat.from(app)
        if (!mgr.areNotificationsEnabled()) return false
        val ch = mgr.getNotificationChannel(ReminderNotifications.CHANNEL_ID)
        ch == null || ch.importance != android.app.NotificationManager.IMPORTANCE_NONE
    }.getOrDefault(false)

    private companion object {
        const val PREFS = "morning_reminder"
        const val K_DAY = "day_id"
        const val K_SENT = "sent_count"
        const val K_DISMISSED = "dismissed_count"
        const val K_EPISODES = "unlock_episodes"
        const val K_LAST_SENT_EP = "last_sent_episode"
        const val K_WAS_IN_USE = "was_in_use"
        const val K_FIRST_USE = "first_use_at_ms"
    }
}
