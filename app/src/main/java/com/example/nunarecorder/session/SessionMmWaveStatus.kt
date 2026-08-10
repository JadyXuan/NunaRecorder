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
)

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
