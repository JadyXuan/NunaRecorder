package com.example.nunarecorder.data

data class UserSettings(
    val userId: String = "",
    val serverHost: String = "10.0.2.2",
    val serverPort: Int = 9000,
    val logLevel: LogLevel = LogLevel.INFO,
    /** 录制结束（或分段封口）后是否自动入队 VAD */
    val autoVadOnRecord: Boolean = true,
    /** 录制时是否按固定时长切分 Opus 段 */
    val segmentEnabled: Boolean = true,
    /** 切片时长（秒），仅 segmentEnabled=true 时生效 */
    val segmentDurationSec: Int = 60
)
