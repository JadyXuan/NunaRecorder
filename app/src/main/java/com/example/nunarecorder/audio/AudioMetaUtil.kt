package com.example.nunarecorder.audio

import java.io.File

object AudioMetaUtil {

    private const val FRAME_SIZE_BYTES = 80L
    private const val FRAME_DURATION_MS = 20L

    fun parseStartTimeFromFileName(name: String): Long? {
        val base = name.removeSuffix(".opus")
        val parts = base.split("_")
        val tsPart = parts.lastOrNull() ?: return null
        return tsPart.toLongOrNull()
    }

    fun computeDurationMsForOpusFile(file: File): Long {
        val totalBytes = file.length()
        if (totalBytes <= 0L) return 0L
        val frameCount = totalBytes / FRAME_SIZE_BYTES
        return frameCount * FRAME_DURATION_MS
    }
}

