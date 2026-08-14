package com.example.nunarecorder.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采集界面上的毫米波那一行。
 *
 * 协调者 2026-08-14：「这条和 GPS 那次是同一类问题：数据在采、在传，但用户看不见，
 * 于是无法判断有没有在工作。GPS 那次是采了整整一天才发现全是网络定位。」
 *
 * 所以这里锁住的不是文案，是**四种状态必须能被区分开**——尤其是
 * 「设备根本没在发」不能显示成「等一下就好」。
 */
class LiveRecordingUiStatsTest {

    private val start = 1_800_000_000_000L

    private fun stats(
        packets: Long = 0L,
        radarOn: Boolean? = null,
        stateEvents: Long = 0L
    ) = LiveRecordingUiStats(
        sessionPath = "/tmp/s",
        totalBytes = 0L,
        closedSegmentCount = 0,
        openSegmentBytes = 0L,
        receivedPackets = 0L,
        startedAtMs = start,
        mmWavePackets = packets,
        mmWaveRadarOn = radarOn,
        mmWaveStateEvents = stateEvents
    )

    @Test
    fun `刚开采还没有毫米波消息不告警`() {
        // A001 遥测在收到第一帧音频之后才订阅，而首帧实测要等约一分钟。
        // 这段时间报警等于每次开采都亮红灯。
        val line = stats().mmWaveLine(start + 60_000L)
        assertFalse(line.alert)
        assertEquals("等待设备上报…", line.text)
    }

    @Test
    fun `久等仍然一条消息都没有要明说这台设备没在发`() {
        // 实测：05_7A（固件 3.14.5.1736）连续 51 分钟一个包都没有。
        val line = stats().mmWaveLine(start + LiveRecordingUiStats.MMWAVE_GRACE_MS + 1)
        assertTrue("这是要换设备的信号，必须告警", line.alert)
        assertTrue(line.text.contains("没有在发"))
    }

    @Test
    fun `正在采集时显示包数`() {
        val line = stats(packets = 1249, radarOn = true).mmWaveLine(start + 3_600_000L)
        assertFalse(line.alert)
        assertTrue(line.text.contains("1,249"))
        assertTrue(line.text.contains("正在采集"))
    }

    @Test
    fun `雷达休眠不是故障`() {
        // 固件自己 30 秒开 / 30 秒关。把休眠显示成异常，一小时要吓佩戴者三十次。
        val line = stats(packets = 600, radarOn = false).mmWaveLine(start + 1_800_000L)
        assertFalse(line.alert)
        assertTrue(line.text.contains("休眠"))
        assertTrue(line.text.contains("600"))
    }

    @Test
    fun `设备说雷达关着和完全没消息是两回事`() {
        // 有 0x13 说明 A001 那一路是通的，问题在设备侧，不是链路。
        val early = stats(stateEvents = 1).mmWaveLine(start + 10_000L)
        assertFalse("刚开始还不用告警", early.alert)
        assertTrue(early.text.contains("设备报告雷达关闭"))

        val late = stats(stateEvents = 1)
            .mmWaveLine(start + LiveRecordingUiStats.MMWAVE_GRACE_MS + 1)
        assertTrue("久了就是真的没有数据", late.alert)
    }

    @Test
    fun `会话开始时间未知时不因为算出负数而误报`() {
        // startedAtMs = 0 是旧状态或未初始化，elapsed 归零，走"等待"分支。
        val line = stats().copy(startedAtMs = 0L).mmWaveLine(start)
        assertFalse(line.alert)
    }
}
