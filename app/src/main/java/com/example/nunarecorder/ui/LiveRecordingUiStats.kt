package com.example.nunarecorder.ui

import com.example.nunarecorder.recording.LiveRecordingStats

/** 采集界面上毫米波那一行；[alert] 为真时用告警色 */
data class MmWaveLine(val text: String, val alert: Boolean)

/**
 * 设备页 / 录音列表共用的「录制进行中」实时统计。
 *
 * 佩戴者原来只能看到"录制中"，而实测里"录制中"的时候链路其实已经断了。
 * 所以这里带上链路健康：最近收到数据的时间、完整度、断连次数。
 */
data class LiveRecordingUiStats(
    val sessionPath: String,
    val totalBytes: Long,
    val closedSegmentCount: Int,
    val openSegmentBytes: Long,
    /** 实到的 20 ms Opus 包数 */
    val receivedPackets: Long,
    /** 按墙钟应有的 20 ms 包数 */
    val expectedPackets: Long = 0L,
    /** 最近一次收到音频帧的墙钟；null = 本次会话一帧都没收到 */
    val lastFrameAtMs: Long? = null,
    val disconnectCount: Int = 0,
    val linkDown: Boolean = false,
    /** 会话开始的墙钟；毫米波要用它判断"等了多久还没来" */
    val startedAtMs: Long = 0L,
    val mmWavePackets: Long = 0L,
    /** 设备最近报告的雷达状态；null = 还没收到过任何 `0x13` */
    val mmWaveRadarOn: Boolean? = null,
    val mmWaveStateEvents: Long = 0L
) {
    fun formatTotalBytes(): String = formatBytes(totalBytes)

    /**
     * 毫米波这一行。
     *
     * **为什么要有这一行**：GPS 那次是采了整整一天才发现全程都是网络定位，
     * 因为界面上根本看不出那一路是断的。毫米波现在处于同样的盲区——数据在采、
     * 在传，但佩戴者不知道有没有在采。而且实测已经出现过一台设备
     * （固件 3.14.5.1736）连续 51 分钟一个毫米波包都没有。
     *
     * 四种状态必须分开，因为对应的动作完全不同：
     * - 在采 → 什么都不用做
     * - 雷达休眠 → 固件每 30 秒一轮，正常，等着就行
     * - 设备说雷达是关的 → 多半是固件旧，要换设备或升级
     * - 一条消息都没有 → 刚开始是正常的（订阅在第一帧音频之后），久了就是坏了
     */
    fun mmWaveLine(nowMs: Long): MmWaveLine {
        val elapsed = if (startedAtMs > 0L) nowMs - startedAtMs else 0L
        return when {
            mmWavePackets > 0L -> MmWaveLine(
                text = when (mmWaveRadarOn) {
                    true -> "正在采集 · 已收 %,d 包".format(mmWavePackets)
                    false -> "雷达休眠中（固件 30 秒一轮）· 已收 %,d 包".format(mmWavePackets)
                    null -> "已收 %,d 包".format(mmWavePackets)
                },
                alert = false
            )

            mmWaveStateEvents > 0L -> MmWaveLine(
                // 设备在说话，只是说"雷达是关的"。这不是链路问题。
                text = "设备报告雷达关闭，暂时没有数据",
                alert = elapsed >= MMWAVE_GRACE_MS
            )

            elapsed < MMWAVE_GRACE_MS -> MmWaveLine("等待设备上报…", alert = false)

            else -> MmWaveLine(
                text = "这台设备没有在发毫米波（可能固件较旧）",
                alert = true
            )
        }
    }

    /** 完整度 0..1：实到 / 按墙钟应有 */
    val completeness: Float
        get() = if (expectedPackets <= 0L) 1f
        else (receivedPackets.toFloat() / expectedPackets).coerceIn(0f, 1f)

    /** 距上次收到帧多久；从未收到过返回 null */
    fun staleForMs(nowMs: Long): Long? = lastFrameAtMs?.let { nowMs - it }

    companion object {
        /** 超过这个时间没收到帧就认为链路不健康（设备正常时 50 帧/秒） */
        const val STALE_THRESHOLD_MS = 5_000L

        /**
         * 会话开始后多久还没有任何毫米波消息才算异常。
         *
         * 三分钟不是保守，是三段真实延迟叠出来的：A001 遥测**在收到第一帧音频之后**
         * 才订阅，而首帧本身实测要等约一分钟；固件的雷达占空又是 30 秒一轮。
         * 定得太短会在正常开采时报警，而一个总在误报的指示灯等于没有指示灯。
         */
        const val MMWAVE_GRACE_MS = 180_000L

        fun from(stats: LiveRecordingStats) = LiveRecordingUiStats(
            sessionPath = stats.sessionDir.absolutePath,
            totalBytes = stats.totalBytes,
            closedSegmentCount = stats.closedSegmentCount,
            openSegmentBytes = stats.openSegmentBytes,
            receivedPackets = stats.receivedPackets,
            expectedPackets = stats.expectedPackets,
            lastFrameAtMs = stats.lastFrameAtMs,
            disconnectCount = stats.disconnectCount,
            linkDown = stats.linkDown,
            startedAtMs = stats.startedAtMs,
            mmWavePackets = stats.mmWavePackets,
            mmWaveRadarOn = stats.mmWaveRadarOn,
            mmWaveStateEvents = stats.mmWaveStateEvents
        )

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
