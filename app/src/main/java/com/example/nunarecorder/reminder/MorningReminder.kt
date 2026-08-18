package com.example.nunarecorder.reminder

import com.example.nunarecorder.recording.CollectionClock
import org.json.JSONObject

/**
 * 「采集日已经开始，但今天还没录过」的提醒逻辑。**不碰任何 Android API**，可直接跑 JVM 单测。
 *
 * ## 为什么不是一个定时通知
 *
 * 实测 08-09：采集日 04:00 开始，首段 08:45，**前 285 分钟系统性全空**，
 * 占当天全部缺失的 55%。而那天戴设备的是设计这套系统的人本人——
 * **他不需要被告知要早戴，他是没想起来。** 教程解决的是"知不知道"，不是"想不想得起来"。
 *
 * 固定钟点的通知会在他还睡着时弹出来、被顺手划掉，**然后再也不出现**——
 * 那比不提醒更糟，因为它制造了"已经提醒过了"的假象。
 * 所以钟点只负责**打开窗口**，真正决定发不发的是[Snapshot.phoneInUse]：
 * 屏幕亮着且没锁 = 人醒着且手机在手上。
 *
 * ## 这个提醒本身是一次干预，所以它必须每天一模一样
 *
 * 如果提醒会"越拖越催"，参与者就会逐日提早佩戴，**戴上时刻在参与者内部随时间漂移**，
 * 于是"第 3 天早晨数据更多"变成干预的产物而不是发现，**任何跨天纵向分析都被污染**——
 * 而那正是 3 天 / 7 天设计本来要支持的分析。
 *
 * 因此 [Progress] **每个采集日从零开始**，不跨天累积、不递进、不因为昨天没戴就今天更凶。
 */
object MorningReminder {

    /** 当天最多提醒几次。1 次会被顺手划掉，无限次会被关掉通知——**上限是这条能不能长期活着的关键** */
    const val MAX_PER_DAY = 2

    /** 轮询周期。只用来开窗口，不要求精确，用 inexact alarm 省电 */
    const val POLL_INTERVAL_MS = 15 * 60_000L

    /** 此刻观察到的世界 */
    data class Snapshot(
        val nowMs: Long,
        val recordingActive: Boolean,
        /** 屏幕亮着**且**没有锁屏 —— 人醒着且手机在手上 */
        val phoneInUse: Boolean,
        val notificationsEnabled: Boolean
    )

    /**
     * 当天累计状态。**只在当天有效**，换采集日就整体重置。
     *
     * [unlockEpisodes] 是"观察到的解锁次数"，用来实现"间隔按解锁次数而不是分钟数"：
     * 同一次解锁里不重复提醒，否则他刷个手机就被连着弹两次。
     *
     * **这是采样出来的，不是事件流**：两次轮询之间的短暂解锁我们看不见。
     * 后果最多是第二次提醒来得晚一点，不会多发。
     */
    data class Progress(
        val dayId: String = "",
        val sentCount: Int = 0,
        val dismissedCount: Int = 0,
        val unlockEpisodes: Int = 0,
        /** 上一次提醒是在第几次解锁里发的；-1 = 还没发过 */
        val lastSentEpisode: Int = -1,
        /** 上一次轮询时手机是不是在用，用来识别"新的一次解锁" */
        val wasInUse: Boolean = false,
        /** 当天第一次观察到手机被使用的时刻；0 = 还没观察到 */
        val firstInUseAtMs: Long = 0L,
        /**
         * 当天闹钟**实际醒来**的次数。
         *
         * **这不是调试计数，是分析时唯一能分层的那一列。**
         *
         * 没有它，`sentCount == 0` 有三种读法，而它们在数据里长得一模一样：
         * ① 提醒没必要发（人自己就戴上了）② 人一直在睡 ③ **闹钟压根没醒**。
         * 有了它：`pollCount > 0 && sentCount == 0` 才是"自然佩戴"，
         * 而 `pollCount == 0` 说明这个功能在这台手机上没跑起来。
         *
         * 更要紧的是它对承重主张的作用：**被提醒的早晨和没被提醒的早晨不是同一个总体**
         * （提醒是干预，它改的是构念不是混淆）。「这个人的常态」只对**自然佩戴**
         * 那一段成立，而把两段分开的依据就是这一列。**参与者标完就没了，事后补不出来。**
         */
        val pollCount: Int = 0,
        /** 当天闹钟最后一次醒来的时刻；0 = 一次都没醒过 */
        val lastPollAtMs: Long = 0L,
        /** 当天第一次真正发出提醒的时刻；0 = 没发过。用来判断"戴上"离提醒有多近 */
        val firstSentAtMs: Long = 0L
    )

    enum class Action {
        /** 发一条提醒 */
        POST,

        /** 什么都不做，继续等下一次轮询 */
        WAIT,

        /** 今天不用再提醒了：已经在录，或者已经发满 */
        STOP_TODAY
    }

    data class Decision(val action: Action, val progress: Progress)

    /**
     * 决定这一次轮询要做什么。**纯函数**：给同样的输入永远给同样的输出。
     */
    fun decide(snapshot: Snapshot, stored: Progress): Decision {
        val today = CollectionClock.dayId(snapshot.nowMs)
        // 换采集日 = 整体重置。跨天不携带任何东西，见类文档里那条干预漂移。
        // 计数在最前面加：**闹钟醒了这件事本身要被记下来**，哪怕这一轮什么都不做，
        // 否则"醒了但没发"和"根本没醒"又变回同一种数据。
        val p0 = (if (stored.dayId == today) stored else Progress(dayId = today))
            .let { it.copy(pollCount = it.pollCount + 1, lastPollAtMs = snapshot.nowMs) }

        // 已经在录：立刻停。一个在你已经照做之后还在响的通知，
        // 是最快让人去关通知的东西——而关掉之后就永久失效了。
        if (snapshot.recordingActive) return Decision(Action.STOP_TODAY, p0)

        val inUse = snapshot.phoneInUse
        val newEpisode = inUse && !p0.wasInUse
        val p = p0.copy(
            wasInUse = inUse,
            unlockEpisodes = if (newEpisode) p0.unlockEpisodes + 1 else p0.unlockEpisodes,
            firstInUseAtMs = if (inUse && p0.firstInUseAtMs == 0L) snapshot.nowMs else p0.firstInUseAtMs
        )

        if (p.sentCount >= MAX_PER_DAY) return Decision(Action.STOP_TODAY, p)
        // 人没在用手机就不发。这一条是整个设计的支点：
        // 睡着时弹出来的通知会被划掉，然后我们以为"已经提醒过了"。
        //
        // ⚠️ **这是一条故意什么都不做的路径**，而这类路径最容易在后来的重构里
        // 被当成 bug "修掉"。所以留下实测记录，不只是留下理由：
        // 2026-08-18 在 API 35 模拟器上强制 deep Doze（mState=IDLE、屏幕关、电池供电），
        // 闹钟按 15 分钟醒来、判定 WAIT、**没有发通知**，并把自己重排到下一轮。
        // 也就是说"醒了但什么都不做"是被验证过的正确行为，不是漏了一个分支。
        if (!inUse) return Decision(Action.WAIT, p)
        // 同一次解锁里不重复发
        if (p.unlockEpisodes == p.lastSentEpisode) return Decision(Action.WAIT, p)

        return Decision(
            Action.POST,
            p.copy(
                sentCount = p.sentCount + 1,
                lastSentEpisode = p.unlockEpisodes,
                firstSentAtMs = if (p.firstSentAtMs == 0L) snapshot.nowMs else p.firstSentAtMs
            )
        )
    }

    /**
     * 写进 `context/context.jsonl` 的一行。
     *
     * **这条比提醒本身更要紧。** 通知被关是永久失效**而且我们看不见**——
     * 参与者仍然在采集，只是每天照样八点半才开始，而我们从数据上分不出
     * 「提醒没用」和「提醒被关了」。等发现 285 分钟没降下来，
     * 根本不知道该改提醒设计还是该改别的。做法照 `gps_status`。
     *
     * @param sessionStartedAtMs 本次会话开始时刻，用来算"距采集日开始多久"
     */
    fun toJsonLine(p: Progress, sessionStartedAtMs: Long, notificationsEnabled: Boolean): String =
        JSONObject().apply {
            put("type", "morning_reminder")
            put("t_ms", sessionStartedAtMs)
            put("collection_day", CollectionClock.dayId(sessionStartedAtMs))
            put("sent_count", p.sentCount)
            put("dismissed_count", p.dismissedCount)
            put("unlock_episodes", p.unlockEpisodes)
            // 见 Progress.pollCount：这三个是分析时的分层依据，不是调试字段
            put("poll_count", p.pollCount)
            if (p.lastPollAtMs > 0L) put("last_poll_at_ms", p.lastPollAtMs)
            if (p.firstSentAtMs > 0L) put("first_sent_at_ms", p.firstSentAtMs)
            // 便于下游直接分层，不必自己推：真正决定"这一天算不算自然佩戴"的就是它
            put("prompted", p.sentCount > 0)
            // false = 提醒这一路已经永久失效，且只有这一行看得出来
            put("notifications_enabled", notificationsEnabled)
            put("max_per_day", MAX_PER_DAY)
            if (p.firstInUseAtMs > 0L) put("first_phone_use_at_ms", p.firstInUseAtMs)
            // 验收指标本身：04:00 → 本次会话开始。首段时间戳也能还原，
            // 但直接写出来省得每个下游各算一套采集日边界。
            put("minutes_from_day_start", minutesFromDayStart(sessionStartedAtMs))
        }.toString()

    /** 采集日开始（本地 04:00）到 [epochMs] 的分钟数 */
    fun minutesFromDayStart(epochMs: Long): Long =
        (epochMs - CollectionClock.dayStart(epochMs)) / 60_000L
}
