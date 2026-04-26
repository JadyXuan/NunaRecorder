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
                mac = obj.optString("mac", ""),
                serverHost = obj.optString("serverHost", "10.0.2.2"),
                serverPort = obj.optInt("serverPort", 9000)
            )
        } catch (_: Exception) {
            UserSettings()
        }
    }

    fun save(settings: UserSettings) {
        val obj = JSONObject().apply {
            put("userId", settings.userId)
            put("mac", settings.mac)
            put("serverHost", settings.serverHost)
            put("serverPort", settings.serverPort)
        }
        sp.edit().putString(KEY_SETTINGS, obj.toString()).apply()
    }
}

