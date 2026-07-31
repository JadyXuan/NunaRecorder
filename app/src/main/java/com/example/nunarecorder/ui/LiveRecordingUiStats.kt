package com.example.nunarecorder.ui

import com.example.nunarecorder.recording.LiveRecordingStats

/**
 * 设备页 / 录音列表共用的「录制进行中」实时统计。
 *
 * 佩戴者原来只能看到"录制中"，而实测里"录制中"的时候链路其实已经断了。
 * 所以这里带上链路健康：最近收到帧的时间、丢帧率、断连次数。
 */
data class LiveRecordingUiStats(
    val sessionPath: String,
    val totalBytes: Long,
    val closedSegmentCount: Int,
    val openSegmentBytes: Long,
    val receivedFrames: Long,
    val lostFrames: Long = 0L,
    /** 最近一次收到音频帧的墙钟；null = 本次会话一帧都没收到 */
    val lastFrameAtMs: Long? = null,
    val disconnectCount: Int = 0,
    val linkDown: Boolean = false
) {
    fun formatTotalBytes(): String = formatBytes(totalBytes)

    /** 序号空洞占应收帧数的比例 */
    val lossRatio: Float
        get() {
            val total = receivedFrames + lostFrames
            return if (total <= 0L) 0f else lostFrames.toFloat() / total
        }

    /** 距上次收到帧多久；从未收到过返回 null */
    fun staleForMs(nowMs: Long): Long? = lastFrameAtMs?.let { nowMs - it }

    companion object {
        /** 超过这个时间没收到帧就认为链路不健康（设备正常时 50 帧/秒） */
        const val STALE_THRESHOLD_MS = 5_000L

        fun from(stats: LiveRecordingStats) = LiveRecordingUiStats(
            sessionPath = stats.sessionDir.absolutePath,
            totalBytes = stats.totalBytes,
            closedSegmentCount = stats.closedSegmentCount,
            openSegmentBytes = stats.openSegmentBytes,
            receivedFrames = stats.receivedFrames,
            lostFrames = stats.lostFrames,
            lastFrameAtMs = stats.lastFrameAtMs,
            disconnectCount = stats.disconnectCount,
            linkDown = stats.linkDown
        )

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
