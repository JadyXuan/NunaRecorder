package com.example.nunarecorder.data

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

class UserSettingsStorage(context: Context) {

    companion object {
        private const val PREF_NAME = "user_settings_prefs"
        private const val KEY_SETTINGS = "user_settings"
        private const val LEGACY_DEFAULT_BASE_URL_SHA256 =
            "78f2ca6035dd4dd7a1ab8061a20061680038a9cb4c5571cdd4554f49695dda3b"

        internal fun migrateBaseUrl(value: String): String {
            val normalized = value.trim().trimEnd('/')
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(normalized.lowercase().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return if (digest == LEGACY_DEFAULT_BASE_URL_SHA256) "" else value.trim()
        }
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
                // v0.1.0-beta.1 shipped a pilot server as the implicit default. Clear that
                // exact value on upgrade so networking becomes explicit. A user who really
                // wants that server can enter it again in Settings.
                baseUrl = migrateBaseUrl(
                    obj.optString("baseUrl", UserSettings.DEFAULT_BASE_URL)
                ),
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
                lifelogEnabled = obj.optBoolean("lifelogEnabled", false),
                annotationPollingEnabled = obj.optBoolean("annotationPollingEnabled", false),
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
            put("baseUrl", settings.baseUrl.trim())
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
