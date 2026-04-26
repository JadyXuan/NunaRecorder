package com.example.nunarecorder.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class DeviceStorage(private val context: Context) {

    companion object {
        private const val PREF_NAME = "paired_devices_prefs"
        private const val KEY_DEVICES = "paired_devices"
    }

    private val sp by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun saveOrUpdateDevice(device: PairedDevice) {
        val list = getPairedDevices().toMutableList()

        // 先移除相同 address 的老记录
        val idx = list.indexOfFirst { it.address == device.address }
        if (idx >= 0) {
            list[idx] = device
        } else {
            list.add(device)
        }
        saveList(list)
    }

    fun getPairedDevices(): List<PairedDevice> {
        val json = sp.getString(KEY_DEVICES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            val result = mutableListOf<PairedDevice>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(
                    PairedDevice(
                        name = obj.optString("name", "").takeIf { it.isNotEmpty() },
                        address = obj.getString("address"),
                        lastConnectedTime = obj.optLong("lastConnectedTime", 0L)
                    )
                )
            }
            result
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun removeDevice(address: String) {
        val list = getPairedDevices().filterNot { it.address == address }
        saveList(list)
    }

    fun clearAll() {
        sp.edit().remove(KEY_DEVICES).apply()
    }

    private fun saveList(list: List<PairedDevice>) {
        val arr = JSONArray()
        list.forEach { d ->
            val obj = JSONObject().apply {
                put("name", d.name)
                put("address", d.address)
                put("lastConnectedTime", d.lastConnectedTime)
            }
            arr.put(obj)
        }
        sp.edit().putString(KEY_DEVICES, arr.toString()).apply()
    }
}
