package com.example.nunarecorder.session

import java.io.File

/** A filesystem-aware view of the millimetre-wave state for list/detail/export UI. */
data class SessionMmWaveStatus(
    val enabled: Boolean,
    val status: String,
    val file: File,
    val hasData: Boolean,
    val packetCount: Long?,
    val payloadBytes: Long?,
    val fileBytes: Long,
    val malformedPackets: Long,
    val droppedPackets: Long
) {
    /**
     * 给界面看的一行。
     *
     * `status` 的四个取值语义差别很大（见 `docs/SESSION_SYNC_PROTOCOL.md` §1.4.1），
     * 直接把英文单词显示出来等于没说。尤其 `waiting` **不是"还在等"，是那次采集没能正常收尾**——
     * 显示成"等待中"会让参与者以为再等等就好。
     */
    fun describe(): String = when {
        hasData -> "已采集 %,d 包 · %s".format(packetCount ?: 0L, formatBytes(fileBytes))
        status == MmWaveSummary.STATUS_NO_DATA -> "没有采到（设备未开启雷达）"
        status == MmWaveSummary.STATUS_WAITING -> "这次采集没有正常结束，毫米波未收尾"
        status == MmWaveSummary.STATUS_DISABLED -> "未启用"
        else -> "无数据"
    }

    /** 值得让参与者注意：启用了却一个包都没有 */
    val alert: Boolean
        get() = enabled && !hasData && status != MmWaveSummary.STATUS_DISABLED

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

fun SessionManifest.resolveMmWaveStatus(sessionDir: File): SessionMmWaveStatus {
    val target = File(sessionDir, mmWave.file)
    val hasData = target.exists() && target.length() > 0L
    return SessionMmWaveStatus(
        enabled = mmWave.enabled || SessionModalities.MMWAVE in contextModalities,
        status = if (hasData) MmWaveSummary.STATUS_CAPTURED else mmWave.status,
        file = target,
        hasData = hasData,
        packetCount = mmWave.packetCount,
        payloadBytes = mmWave.payloadBytes,
        fileBytes = if (hasData) target.length() else (mmWave.fileBytes ?: 0L),
        malformedPackets = mmWave.malformedPackets,
        droppedPackets = mmWave.droppedPackets
    )
}
