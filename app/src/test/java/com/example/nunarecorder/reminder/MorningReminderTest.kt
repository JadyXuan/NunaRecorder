package com.example.nunarecorder.reminder

import com.example.nunarecorder.recording.CollectionClock
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * T-2026-08-15-047：采集日前 285 分钟系统性全空（08-09 实测，占当天全部缺失的 55%）。
 *
 * 这里锁的不是"通知能不能弹出来"，是四条会决定这个提醒**能不能长期活着**的规则：
 * 睡着时不发、开始录了立刻停、当天有上限、以及**跨天完全不变**。
 */
class MorningReminderTest {

    private val tz = TimeZone.getTimeZone(CollectionClock.TIMEZONE_ID)

    /** 构造某个本地时刻的 epoch 毫秒 */
    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(tz).apply {
            // 严格模式：hour=40 这种越界要当场炸掉。宽松模式会静默进位到第二天，
            // 而这个测试恰恰在验"跨不跨采集日"，静默进位会让用例测的是别的东西。
            isLenient = false
            set(2026, Calendar.AUGUST, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun snap(
        ms: Long,
        recording: Boolean = false,
        inUse: Boolean = true,
        notif: Boolean = true
    ) = MorningReminder.Snapshot(ms, recording, inUse, notif)

    @Test
    fun `人还睡着就不发`() {
        // 固定钟点的通知会在他睡着时弹出来、被顺手划掉，然后再也不出现——
        // 那比不提醒更糟，因为它制造了"已经提醒过了"的假象。
        val d = MorningReminder.decide(snap(at(9, 5), inUse = false), MorningReminder.Progress())
        assertEquals(MorningReminder.Action.WAIT, d.action)
        assertEquals(0, d.progress.sentCount)
    }

    @Test
    fun `醒着并解锁之后才发第一次`() {
        var p = MorningReminder.decide(snap(at(9, 5), inUse = false), MorningReminder.Progress()).progress
        val d = MorningReminder.decide(snap(at(9, 7)), p)
        assertEquals(MorningReminder.Action.POST, d.action)
        assertEquals(1, d.progress.sentCount)
        assertEquals(1, d.progress.unlockEpisodes)
    }

    @Test
    fun `同一次解锁里不重复发`() {
        // 否则他刷个手机就被连着弹两次，而上限一共才 2 次
        var p = MorningReminder.decide(snap(at(9, 7)), MorningReminder.Progress()).progress
        repeat(3) {
            val d = MorningReminder.decide(snap(at(9, 7 + it)), p)
            assertEquals(MorningReminder.Action.WAIT, d.action)
            p = d.progress
        }
        assertEquals(1, p.sentCount)
    }

    @Test
    fun `锁屏之后再解锁才发第二次，而且到此为止`() {
        var p = MorningReminder.decide(snap(at(9, 7)), MorningReminder.Progress()).progress
        p = MorningReminder.decide(snap(at(9, 7, 20), inUse = false), p).progress   // 锁屏
        val second = MorningReminder.decide(snap(at(9, 7, 40)), p)
        assertEquals(MorningReminder.Action.POST, second.action)
        assertEquals(2, second.progress.sentCount)

        // 上限到了：第三次解锁不再发。无限提醒会被关掉通知，而那是永久失效。
        p = MorningReminder.decide(snap(at(9, 7, 50), inUse = false), second.progress).progress
        val third = MorningReminder.decide(snap(at(9, 8, 10)), p)
        assertEquals(MorningReminder.Action.STOP_TODAY, third.action)
        assertEquals(MorningReminder.MAX_PER_DAY, third.progress.sentCount)
    }

    @Test
    fun `开始录制就立刻停，哪怕一次都还没发`() {
        // 在你已经照做之后还在响的通知，是最快让人去关通知的东西
        val d = MorningReminder.decide(snap(at(9, 8), recording = true), MorningReminder.Progress())
        assertEquals(MorningReminder.Action.STOP_TODAY, d.action)
    }

    @Test
    fun `换采集日整体重置，不携带任何东西`() {
        // ⚠️ 这条是本单唯一由它自己制造的风险：如果提醒会"越拖越催"，
        // 参与者就会逐日提早佩戴，戴上时刻在参与者内部随时间漂移，
        // 于是"第 3 天早晨数据更多"变成干预的产物而不是发现，纵向分析被污染。
        var p = MorningReminder.decide(snap(at(9, 7)), MorningReminder.Progress()).progress
        p = MorningReminder.decide(snap(at(9, 7, 30), inUse = false), p).progress
        p = MorningReminder.decide(snap(at(9, 7, 45)), p).progress
        assertEquals(MorningReminder.MAX_PER_DAY, p.sentCount)

        // 第二天同一时刻：行为必须和第一天的第一次完全一样
        val nextDay = MorningReminder.decide(snap(at(10, 7, 0)), p)
        assertEquals(MorningReminder.Action.POST, nextDay.action)
        assertEquals("跨天不能累积发送次数", 1, nextDay.progress.sentCount)
        assertEquals("跨天不能累积解锁计数", 1, nextDay.progress.unlockEpisodes)
        assertEquals(0, nextDay.progress.dismissedCount)
    }

    @Test
    fun `凌晨三点半仍属于前一个采集日`() {
        // 采集日切点是 04:00。用日历日会把熬夜那段劈到第二天，
        // 于是凌晨三点还在录的人第二天一早又被从零开始提醒。
        val late = at(10, 3, 30)
        assertEquals(CollectionClock.dayId(at(9, 20)), CollectionClock.dayId(late))

        var p = MorningReminder.decide(snap(at(9, 7)), MorningReminder.Progress()).progress
        p = MorningReminder.decide(snap(at(9, 7, 30), inUse = false), p).progress
        p = MorningReminder.decide(snap(at(9, 7, 45)), p).progress
        // 同一采集日内，凌晨三点半不该重新开始
        assertEquals(MorningReminder.Action.STOP_TODAY, MorningReminder.decide(snap(late), p).action)
    }

    @Test
    fun `写进 context 的那一行要能分辨"提醒被关了"`() {
        // 通知被关是永久失效而且看不见：他仍然在采集，只是每天照样八点半才开始，
        // 而我们从数据上分不出"提醒没用"和"提醒被关了"。
        val p = MorningReminder.Progress(
            dayId = CollectionClock.dayId(at(9, 9)),
            sentCount = 2, dismissedCount = 2, unlockEpisodes = 5,
            firstInUseAtMs = at(9, 7)
        )
        val j = JSONObject(MorningReminder.toJsonLine(p, at(9, 9), notificationsEnabled = false))

        assertEquals("morning_reminder", j.getString("type"))
        assertFalse("这一位是唯一能看出提醒已永久失效的地方", j.getBoolean("notifications_enabled"))
        assertEquals(2, j.getInt("sent_count"))
        assertEquals("划掉次数是'提醒无效'和'他有意不录'的唯一区分依据", 2, j.getInt("dismissed_count"))
        // 验收指标本身：04:00 → 本次会话开始
        assertEquals(5 * 60L, j.getLong("minutes_from_day_start"))
        assertEquals(CollectionClock.dayId(at(9, 9)), j.getString("collection_day"))
    }

    @Test
    fun `采集日起点是本地四点`() {
        assertEquals(at(9, 4), CollectionClock.dayStart(at(9, 9)))
        assertEquals("凌晨三点属于前一天的采集日", at(9, 4), CollectionClock.dayStart(at(10, 3)))
        assertEquals(285L, MorningReminder.minutesFromDayStart(at(9, 8, 45)))
    }

    @Test
    fun `闹钟每次醒来都要计数，哪怕这一轮什么都不做`() {
        // 没有这个计数，sentCount == 0 有三种读法而数据里长得一样：
        // 提醒没必要发 / 人一直在睡 / **闹钟压根没醒**。
        var p = MorningReminder.Progress()
        // 人在睡：WAIT
        p = MorningReminder.decide(snap(at(9, 5), inUse = false), p).progress
        assertEquals(1, p.pollCount)
        // 已经在录：STOP_TODAY——照样算醒过一次
        p = MorningReminder.decide(snap(at(9, 6), recording = true), p).progress
        assertEquals(2, p.pollCount)
        assertEquals(0, p.sentCount)
        assertEquals(at(9, 6), p.lastPollAtMs)
    }

    @Test
    fun `自然佩戴和功能没跑起来必须分得开`() {
        // 这是这几个字段存在的全部理由：
        //   pollCount > 0 且 sentCount == 0  → 闹钟活着，人自己戴上的 → **自然佩戴**
        //   pollCount == 0                   → 闹钟没醒，这台手机上功能没跑起来
        val natural = MorningReminder.decide(
            snap(at(9, 7), recording = true), MorningReminder.Progress()
        ).progress
        assertTrue("闹钟醒过", natural.pollCount > 0)
        assertEquals("但没提醒过", 0, natural.sentCount)

        val neverRan = MorningReminder.Progress(dayId = CollectionClock.dayId(at(9, 7)))
        assertEquals("闹钟一次都没醒", 0, neverRan.pollCount)
    }

    @Test
    fun `被提醒过的一天要能一眼看出来`() {
        // 「这个人的常态」只对自然佩戴那段成立——被提醒的早晨是另一个总体。
        var p = MorningReminder.decide(snap(at(9, 7)), MorningReminder.Progress()).progress
        assertEquals(at(9, 7), p.firstSentAtMs)

        val j = JSONObject(MorningReminder.toJsonLine(p, at(9, 8), notificationsEnabled = true))
        assertTrue("prompted 要直接可读，别让下游自己推", j.getBoolean("prompted"))
        assertTrue(j.getInt("poll_count") > 0)
        assertEquals(at(9, 7), j.getLong("first_sent_at_ms"))

        val untouched = JSONObject(
            MorningReminder.toJsonLine(
                MorningReminder.Progress(pollCount = 3), at(9, 8), notificationsEnabled = true
            )
        )
        assertFalse("没提醒过的那天必须是 false", untouched.getBoolean("prompted"))
        assertEquals(3, untouched.getInt("poll_count"))
    }

    @Test
    fun `分层用的那几个字段也跨天重置`() {
        var p = MorningReminder.decide(snap(at(9, 7)), MorningReminder.Progress()).progress
        p = MorningReminder.decide(snap(at(9, 7, 30), inUse = false), p).progress
        assertEquals(2, p.pollCount)

        val nextDay = MorningReminder.decide(snap(at(10, 7, 0)), p).progress
        assertEquals("跨天不能累积轮询计数", 1, nextDay.pollCount)
        assertEquals("跨天不能带着昨天的首发时刻", at(10, 7, 0), nextDay.firstSentAtMs)
    }

    @Test
    fun `没有通知权限时仍然照常推进状态，不要静默假装发过`() {
        // 权限没了也要走完 decide，这样 sent_count 反映的是"我们试图发了几次"，
        // 配合 notifications_enabled=false 才能还原真相
        val d = MorningReminder.decide(snap(at(9, 7), notif = false), MorningReminder.Progress())
        assertEquals(MorningReminder.Action.POST, d.action)
        assertTrue(d.progress.sentCount > 0)
    }
}
