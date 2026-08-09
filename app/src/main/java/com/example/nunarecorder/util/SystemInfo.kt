package com.example.nunarecorder.util

import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * 排障时第一批要问的事实，全部从系统读，不让人手打。
 *
 * 用户 2026-08-09：「app 里最好有个地方也能显示系统版本。」
 * 现在出问题时的沟通成本主要花在这里——"你装的是哪个版本""安卓几""什么手机"，
 * 而这些手机自己都精确知道。研究员手打的是同一个事实的一份更差的拷贝
 * （会打错、会写成不同格式、现场人多手忙最容易空着不填，见 T-2026-08-07-018）。
 *
 * 只收**型号级别**的信息。不读 IMEI / Android ID / 序列号 / 广告 ID——
 * 那些会把假名化的参与者表变成可追踪的设备台账。
 */
data class SystemInfo(
    val appVersionName: String,
    val appVersionCode: Long,
    val androidRelease: String,
    val sdkInt: Int,
    val phoneModel: String,
    val ignoringBatteryOptimizations: Boolean,
    /** Nuna 设备的固件版本；没连过或读不到时为 null */
    val deviceFirmware: String?
) {
    val appVersion: String get() = "$appVersionName ($appVersionCode)"
    val androidVersion: String get() = "Android $androidRelease (API $sdkInt)"

    /** 一行纯文本，方便用户直接复制发给我们 */
    fun toShareText(): String = buildString {
        appendLine("App $appVersion")
        appendLine("系统 $androidVersion")
        appendLine("机型 $phoneModel")
        appendLine("后台保活 ${if (ignoringBatteryOptimizations) "已豁免" else "未豁免"}")
        appendLine("设备固件 ${deviceFirmware ?: "未知"}")
    }.trim()

    companion object {
        fun read(context: Context, deviceFirmware: String? = null): SystemInfo {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            var name = "unknown"
            var code = 0L
            runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                name = info.versionName ?: "unknown"
                code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION") info.versionCode.toLong()
                }
            }
            return SystemInfo(
                appVersionName = name,
                appVersionCode = code,
                androidRelease = Build.VERSION.RELEASE ?: "unknown",
                sdkInt = Build.VERSION.SDK_INT,
                // 和诊断日志头部用同一个来源，两边对得上
                phoneModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                ignoringBatteryOptimizations =
                    pm?.isIgnoringBatteryOptimizations(context.packageName) == true,
                deviceFirmware = deviceFirmware
            )
        }
    }
}
