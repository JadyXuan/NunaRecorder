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
    val segmentDurationSec: Int = 60,
    /** 是否从 lifelog 服务拉取活动时间轴与主动标注问题 */
    val lifelogEnabled: Boolean = true,
    /** 是否允许 WorkManager 在后台轮询待标注事件 */
    val annotationPollingEnabled: Boolean = true,
    /** 是否自动上传已经封口的 Opus 分段。默认关闭，需用户明确开启。 */
    val autoUploadEnabled: Boolean = false,
    /** 自动上传是否仅使用非计费网络（通常为 Wi-Fi）。 */
    val autoUploadWifiOnly: Boolean = true
)
