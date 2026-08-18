package com.example.nunarecorder.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import com.example.nunarecorder.recording.RecordingController
import com.example.nunarecorder.util.DiagnosticsLog

/**
 * 早晨提醒的闹钟与调度。
 *
 * ## 为什么是"闹钟轮询"而不是监听解锁事件
 *
 * 想要的信号是 `ACTION_USER_PRESENT`（解锁），但它**不能在 manifest 里静态注册**
 * （Android 8 起的隐式广播限制），只有活着的进程动态注册才收得到——
 * 而这个提醒恰恰是在"没有会话、进程随时可能已经没了"的时候要工作的。
 *
 * 所以改成：**闹钟只负责把进程唤醒一下并打开窗口，发不发由当时的手机状态决定**。
 * 每次醒来看两件事——屏幕亮着吗、锁屏解了吗——两个都成立就是
 * 「人醒着且手机在手上」，也就是工单要的那个语义，只是用采样而不是事件拿到。
 *
 * 代价写清楚：**两次轮询之间的短暂解锁我们看不见**，后果最多是第二次提醒来得晚，
 * 不会多发。用 inexact 闹钟，系统可以合并，省电。
 */
class MorningReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_POLL = "com.example.nunarecorder.action.MORNING_POLL"
        const val ACTION_DISMISSED = "com.example.nunarecorder.action.MORNING_DISMISSED"
        private const val REQUEST_POLL = 7301

        private fun pollIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_POLL,
            Intent(context, MorningReminderReceiver::class.java).setAction(ACTION_POLL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /**
         * 排下一次轮询。重复调用是安全的（同一个 PendingIntent 会被替换）。
         *
         * 用 `setAndAllowWhileIdle`：不需要精确，但需要 Doze 期间也能醒——
         * 早上七点手机多半正处在 Doze 里，而那正是我们要抓的时刻。
         */
        fun schedule(context: Context, delayMs: Long = MorningReminder.POLL_INTERVAL_MS) {
            val app = context.applicationContext
            ReminderNotifications.ensureChannel(app)
            runCatching {
                (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                    .setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + delayMs,
                        pollIntent(app)
                    )
            }.onFailure { DiagnosticsLog.log("Reminder", "排闹钟失败：${it.message}") }
        }

        /** 今天不用再提醒了：取消闹钟和已经挂着的通知 */
        fun stopForToday(context: Context) {
            val app = context.applicationContext
            runCatching {
                (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                    .cancel(pollIntent(app))
            }
            ReminderNotifications.cancel(app)
        }

        /**
         * 开始录制时调用。**立刻停掉当天的提醒**——
         * 一个在你已经照做之后还在响的通知，是最快让人去关通知的东西，
         * 而通知一旦被关就是永久失效。
         */
        fun onRecordingStarted(context: Context) {
            stopForToday(context)
            DiagnosticsLog.log("Reminder", "已开始录制，停止当天早晨提醒")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DISMISSED -> {
                MorningReminderStore(context).recordDismissed()
                DiagnosticsLog.log("Reminder", "参与者划掉了早晨提醒")
                // 划掉不代表今天结束：还允许再提醒一次（上限 MAX_PER_DAY），
                // 但要等到**下一次解锁**，不是过几分钟又弹。
                schedule(context)
            }
            else -> poll(context)
        }
    }

    private fun poll(context: Context) {
        val app = context.applicationContext
        val store = MorningReminderStore(app)
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = app.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val inUse = (pm?.isInteractive == true) && (km?.isKeyguardLocked != true)
        val notifOk = store.notificationsEnabled()

        val decision = MorningReminder.decide(
            MorningReminder.Snapshot(
                nowMs = System.currentTimeMillis(),
                // 服务在录就一定有 stats；两个信号任一为真都算在录，
                // 宁可少提醒也不要在他已经开始之后还弹
                recordingActive = RecordingController.link.value.isSessionActive ||
                    RecordingController.stats.value != null,
                phoneInUse = inUse,
                notificationsEnabled = notifOk
            ),
            store.load()
        )
        store.save(decision.progress)

        // **每次醒来都记一行，不只是发提醒的时候。**
        //
        // 原来只有 POST 分支写日志，于是「闹钟醒了但人在睡」和「闹钟根本没醒」
        // 写出来的数据一模一样——都是什么都没有。而 T-047 的存亡条件恰恰是
        // **"Doze 下闹钟到底醒不醒"**，那条验收标准用只记 POST 的版本**测不出来**：
        // 采一整天回来看到 `sent_count: 0`，仍然分不清是提醒没必要发，
        // 还是这个功能压根没跑起来。
        DiagnosticsLog.log(
            "Reminder",
            "闹钟醒了 · 手机在用=$inUse · 决定=${decision.action} · " +
                "已发=${decision.progress.sentCount}/${MorningReminder.MAX_PER_DAY} · " +
                "解锁=${decision.progress.unlockEpisodes} · 通知权限=$notifOk"
        )

        when (decision.action) {
            MorningReminder.Action.STOP_TODAY -> stopForToday(app)
            MorningReminder.Action.WAIT -> schedule(app)
            MorningReminder.Action.POST -> {
                ReminderNotifications.ensureChannel(app)
                runCatching {
                    NotificationManagerCompat.from(app).notify(
                        ReminderNotifications.NOTIFICATION_ID,
                        ReminderNotifications.build(app)
                    )
                }.onFailure { DiagnosticsLog.log("Reminder", "发提醒失败：${it.message}") }
                DiagnosticsLog.log(
                    "Reminder",
                    "已提醒第 ${decision.progress.sentCount}/${MorningReminder.MAX_PER_DAY} 次" +
                        "（第 ${decision.progress.unlockEpisodes} 次解锁）"
                )
                schedule(app)
            }
        }
    }
}

/** 开机后把闹钟排回来。不排的话重启一次这条就永久静默，而且没人看得出来。 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        MorningReminderReceiver.schedule(context)
        DiagnosticsLog.log("Reminder", "开机后重排早晨提醒闹钟")
    }
}
