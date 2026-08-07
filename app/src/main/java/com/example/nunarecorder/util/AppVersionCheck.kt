package com.example.nunarecorder.util

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 比对线上 APK 版本。参与者手上装的可能是几版之前的包，而他们不会主动去查。
 *
 * 只提示、不自动下载安装：静默替换正在采集的 App 会中断当天的会话，
 * 而升级的时机应当由人选（比如当天采集结束之后）。
 */
object AppVersionCheck {

    data class Result(val hasUpdate: Boolean, val latestName: String, val latestCode: Long)

    fun check(client: OkHttpClient, serverUrl: String, currentCode: Long): Result? {
        val request = Request.Builder().url("$serverUrl/apk/version").get().build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val o = JSONObject(resp.body?.string() ?: return null)
                val code = o.optLong("versionCode", -1)
                if (code < 0) return null
                Result(
                    hasUpdate = code > currentCode,
                    latestName = o.optString("versionName", "?"),
                    latestCode = code
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
