package com.example.nunarecorder.recording

import com.example.nunarecorder.data.UserSettings

data class RecordingOptions(
    val segmentEnabled: Boolean,
    val segmentDurationMs: Long,
    val autoVadOnRecord: Boolean,
    val mmWaveCaptureEnabled: Boolean = false
) {
    companion object {
        fun from(settings: UserSettings) = RecordingOptions(
            segmentEnabled = settings.segmentEnabled,
            segmentDurationMs = settings.segmentDurationSec.coerceIn(10, 600) * 1000L,
            autoVadOnRecord = settings.autoVadOnRecord,
            mmWaveCaptureEnabled = settings.mmWaveCaptureEnabled
        )
    }
}
