package com.example.nunarecorder.data

/**
 * 用户可调的偏好。
 *
 * **服务器地址、参与者编号、上传令牌都不在这里** —— 它们只来自入组码
 * （`enroll/EnrollmentStore`），App 里不保留任何默认值。手填这三项在 2026-07-31
 * 和 08-03 两次实测都出过问题，配置错了要采完一整天才发现。
 */
data class UserSettings(
    val logLevel: LogLevel = LogLevel.INFO,
    /** 录制结束（或分段封口）后是否自动入队 VAD */
    val autoVadOnRecord: Boolean = true
)
