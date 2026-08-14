package com.example.nunarecorder.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class DeviceStorage(private val context: Context) {

    companion object {
        private const val PREF_NAME = "paired_devices_prefs"
        private const val KEY_DEVICES = "paired_devices"

        /**
         * 合并一台设备的记录。**纯函数，因为这里的错误是静默的**——
         * SharedPreferences 要 Android Context，跑不了 JVM 单测，
         * 所以把有风险的那部分抽出来单独锁住。
         *
         * `lastFirmware` 合并而不是覆盖：调用方多数只想更新"最近连接时间"，
         * 会现构一个 `PairedDevice(name, address, now)`，固件是 null。
         * 整条替换会把好不容易采到的固件抹掉，而它只有采集跑起来之后才拿得到
         * （开采前读到的是 DIS 的 `1.0.0`，见 [DeviceFirmwarePolicy]），
         * 抹掉一次就要重采一次，且界面上完全看不出来。
         */
        internal fun upsert(list: List<PairedDevice>, device: PairedDevice): List<PairedDevice> {
            val idx = list.indexOfFirst { it.address == device.address }
            if (idx < 0) return list + device
            return list.toMutableList().also {
                it[idx] = device.copy(lastFirmware = device.lastFirmware ?: list[idx].lastFirmware)
            }
        }

        /**
         * 记下权威固件版本。没有变化时**返回原列表本身**（引用相等），
         * 调用方据此跳过写盘——这条每次收到 A001 `0x05` 都会走一遍。
         *
         * 只接受可信来源：DIS 那个 `1.0.0` 存进去等于把"未知"记成"已知且正常"，
         * 那条检查就永远放行，比不存更糟。
         */
        internal fun withFirmware(
            list: List<PairedDevice>,
            address: String,
            version: String
        ): List<PairedDevice> {
            if (!DeviceFirmwarePolicy.isTrustworthy(version)) return list
            val idx = list.indexOfFirst { it.address == address }
            if (idx < 0 || list[idx].lastFirmware == version) return list
            return list.toMutableList().also { it[idx] = list[idx].copy(lastFirmware = version) }
        }
    }

    private val sp by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 更新一台设备。
     *
     * **`lastFirmware` 是合并而不是覆盖的**：调用方多数只想更新"最近连接时间"，
     * 会现构一个 `PairedDevice(name, address, now)`，其中固件是 null。
     * 直接整条替换会把好不容易采到的固件抹掉——而它只有采集跑起来之后才拿得到，
     * 抹掉一次就要重采一次才能补回来，且界面上完全看不出来。
     */
    fun saveOrUpdateDevice(device: PairedDevice) {
        saveList(upsert(getPairedDevices(), device))
    }

    /**
     * 记下这台设备的权威固件版本。
     *
     * 只接受可信来源（见 [DeviceFirmwarePolicy.isTrustworthy]）——DIS 那个 `1.0.0`
     * 存进去等于把"未知"记成"已知且正常"，比不存更糟。
     */
    fun recordFirmware(address: String, version: String) {
        val current = getPairedDevices()
        val next = withFirmware(current, address, version)
        if (next !== current) saveList(next)
    }

    fun firmwareOf(address: String?): String? =
        address?.let { a -> getPairedDevices().find { it.address == a }?.lastFirmware }

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
                        lastConnectedTime = obj.optLong("lastConnectedTime", 0L),
                        // 旧记录没有这个键，读成 null = "还没采过，不知道"
                        lastFirmware = obj.optString("lastFirmware", "").takeIf { it.isNotEmpty() }
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
                d.lastFirmware?.let { put("lastFirmware", it) }
            }
            arr.put(obj)
        }
        sp.edit().putString(KEY_DEVICES, arr.toString()).apply()
    }
}
