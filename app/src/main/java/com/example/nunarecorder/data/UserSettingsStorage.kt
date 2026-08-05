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
                logLevel = LogLevel.entries.find {
                    it.name.equals(obj.optString("logLevel", "INFO"), ignoreCase = true)
                } ?: LogLevel.INFO,
                autoVadOnRecord = obj.optBoolean("autoVadOnRecord", true)
            )
        } catch (_: Exception) {
            UserSettings()
        }
    }

    fun save(settings: UserSettings) {
        val obj = JSONObject().apply {
            put("logLevel", settings.logLevel.name)
            put("autoVadOnRecord", settings.autoVadOnRecord)
        }
        sp.edit().putString(KEY_SETTINGS, obj.toString()).apply()
    }
}
