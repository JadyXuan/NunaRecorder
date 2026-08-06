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
            val defaults = UserSettings()
            UserSettings(
                userId = obj.optString("userId", UserSettings.DEFAULT_USER_ID)
                    .ifBlank { UserSettings.DEFAULT_USER_ID },
                // Existing installations do not have baseUrl. Move those installations
                // to the deployed HTTPS service instead of retaining emulator-only hosts.
                baseUrl = obj.optString("baseUrl", UserSettings.DEFAULT_BASE_URL)
                    .ifBlank { UserSettings.DEFAULT_BASE_URL },
                basicAuthUsername = obj.optString(
                    "basicAuthUsername",
                    defaults.basicAuthUsername
                ).ifBlank { defaults.basicAuthUsername },
                basicAuthPassword = obj.optString(
                    "basicAuthPassword",
                    defaults.basicAuthPassword
                ).ifBlank { defaults.basicAuthPassword },
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
            put("baseUrl", settings.baseUrl.trim().ifBlank { UserSettings.DEFAULT_BASE_URL })
            put("basicAuthUsername", settings.basicAuthUsername)
            put("basicAuthPassword", settings.basicAuthPassword)
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
