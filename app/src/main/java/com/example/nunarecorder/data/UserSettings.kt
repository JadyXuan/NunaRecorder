package com.example.nunarecorder.data

data class UserSettings(
    val userId: String = "",
    val serverHost: String = "10.0.2.2",
    val serverPort: Int = 9000,
    /** 上传令牌，对应 Receiver 的 UPLOAD_TOKEN；为空时 session v1 会被服务端拒绝 */
    val uploadToken: String = "",
    val logLevel: LogLevel = LogLevel.INFO,
    /** 录制结束（或分段封口）后是否自动入队 VAD */
    val autoVadOnRecord: Boolean = true
)
