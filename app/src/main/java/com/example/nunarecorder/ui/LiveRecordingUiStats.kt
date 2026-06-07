package com.example.nunarecorder.ui

/** 设备页 / 录音列表共用的「录制进行中」实时统计 */
data class LiveRecordingUiStats(
    val sessionPath: String,
    val totalBytes: Long,
    val closedSegmentCount: Int,
    val openSegmentBytes: Long,
    val blePacketCount: Long
) {
    fun formatTotalBytes(): String = formatBytes(totalBytes)

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
