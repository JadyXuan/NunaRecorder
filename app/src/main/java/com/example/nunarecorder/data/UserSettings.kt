package com.example.nunarecorder.data

import com.example.nunarecorder.BuildConfig
import java.net.URI

data class UserSettings(
    val userId: String = DEFAULT_USER_ID,
    /** 完整服务器根地址，可包含 HTTPS scheme、端口和部署路径前缀。 */
    val baseUrl: String = DEFAULT_BASE_URL,
    /** Pilot 环境入口的 Basic Auth；密码由本机构建配置注入，不写入 Git。 */
    val basicAuthUsername: String = BuildConfig.PILOT_BASIC_AUTH_USERNAME,
    val basicAuthPassword: String = BuildConfig.PILOT_BASIC_AUTH_PASSWORD,
    val logLevel: LogLevel = LogLevel.INFO,
    /** 录制结束（或分段封口）后是否自动入队 VAD */
    val autoVadOnRecord: Boolean = true,
    /** 录制时是否按固定时长切分 Opus 段 */
    val segmentEnabled: Boolean = true,
    /** 切片时长（秒），仅 segmentEnabled=true 时生效 */
    val segmentDurationSec: Int = 60,
    /** 是否从 lifelog 服务拉取活动时间轴与主动标注问题 */
    val lifelogEnabled: Boolean = false,
    /** 是否允许 WorkManager 在后台轮询待标注事件 */
    val annotationPollingEnabled: Boolean = false,
    /** 是否自动上传已经封口的 Opus 分段。默认关闭，需用户明确开启。 */
    val autoUploadEnabled: Boolean = false,
    /** 自动上传是否仅使用非计费网络（通常为 Wi-Fi）。 */
    val autoUploadWifiOnly: Boolean = true
) {
    fun apiBaseUrl(): String =
        baseUrl.trim().trimEnd('/')

    fun hasValidServerUrl(): Boolean {
        val normalized = apiBaseUrl()
        if (normalized.isBlank()) return false
        return runCatching { URI(normalized) }.getOrNull()?.let { uri ->
            (uri.scheme.equals("https", ignoreCase = true) ||
                uri.scheme.equals("http", ignoreCase = true)) &&
                !uri.host.isNullOrBlank() &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                uri.userInfo == null
        } == true
    }

    fun serverConfigurationError(requireUserId: Boolean = true): String? = when {
        baseUrl.isBlank() -> "请先在设置中填写服务器 Base URL"
        !hasValidServerUrl() -> "服务器 Base URL 必须是有效的 http:// 或 https:// 地址"
        requireUserId && userId.isBlank() -> "请先在设置中填写用户 ID"
        else -> null
    }

    companion object {
        const val DEFAULT_BASE_URL = ""
        const val DEFAULT_USER_ID = ""
    }
}
