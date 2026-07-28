package com.example.nunarecorder.data

import android.content.Context
import org.json.JSONObject

class UserSettingsStorage(context: Context) {

    companion object {
        private const val PREF_NAME = "user_settings_prefs"
        private const val KEY_SETTINGS = "user_settings"
    }

    private val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun load(): UserSettings {
        val json = sp.getString(KEY_SETTINGS, null) ?: return UserSettings()
        return try {
            val obj = JSONObject(json)
            UserSettings(
                userId = obj.optString("userId", ""),
                serverHost = obj.optString("serverHost", "10.0.2.2"),
                serverPort = obj.optInt("serverPort", 9000),
                logLevel = LogLevel.entries.find {
                    it.name.equals(obj.optString("logLevel", "INFO"), ignoreCase = true)
                } ?: LogLevel.INFO,
                autoVadOnRecord = obj.optBoolean("autoVadOnRecord", true),
                segmentEnabled = obj.optBoolean("segmentEnabled", true),
                segmentDurationSec = obj.optInt("segmentDurationSec", 60).coerceIn(10, 600),
                lifelogEnabled = obj.optBoolean("lifelogEnabled", true),
                annotationPollingEnabled = obj.optBoolean("annotationPollingEnabled", true),
                autoUploadEnabled = obj.optBoolean("autoUploadEnabled", false),
                autoUploadWifiOnly = obj.optBoolean("autoUploadWifiOnly", true)
            )
        } catch (_: Exception) {
            UserSettings()
        }
    }

    fun save(settings: UserSettings) {
        val obj = JSONObject().apply {
            put("userId", settings.userId)
            put("serverHost", settings.serverHost)
            put("serverPort", settings.serverPort)
            put("logLevel", settings.logLevel.name)
            put("autoVadOnRecord", settings.autoVadOnRecord)
            put("segmentEnabled", settings.segmentEnabled)
            put("segmentDurationSec", settings.segmentDurationSec)
            put("lifelogEnabled", settings.lifelogEnabled)
            put("annotationPollingEnabled", settings.annotationPollingEnabled)
            put("autoUploadEnabled", settings.autoUploadEnabled)
            put("autoUploadWifiOnly", settings.autoUploadWifiOnly)
        }
        sp.edit().putString(KEY_SETTINGS, obj.toString()).apply()
    }
}
