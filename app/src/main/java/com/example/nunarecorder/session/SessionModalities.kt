package com.example.nunarecorder.session

/** Stable modality names shared by manifest.json and context/context.jsonl metadata. */
object SessionModalities {
    const val IMU = "imu"
    const val GPS = "gps"
    const val ACTIVITY = "activity"
    const val MMWAVE = "mmwave"

    fun forRecording(mmWaveEnabled: Boolean): List<String> = buildList {
        add(IMU)
        add(GPS)
        add(ACTIVITY)
        if (mmWaveEnabled) add(MMWAVE)
    }
}
