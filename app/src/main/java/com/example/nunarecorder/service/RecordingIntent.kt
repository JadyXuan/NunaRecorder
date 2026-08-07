package com.example.nunarecorder.service

import android.content.Context

/**
 * 「用户希望正在采集」这个意图，跨进程存活。
 *
 * 前台服务不足以保证不被杀——vivo / 华为 / 小米都会在内存吃紧或用户划掉任务卡片时
 * 连带干掉进程。系统之后用 `START_STICKY` 把服务拉回来时 `intent` 是 null，
 * 光看那个参数无从知道该连哪台设备。
 *
 * 存的只有设备名和地址，没有令牌也没有参与者标识。
 */
object RecordingIntent {

    private const val PREF = "recording_intent"
    private const val KEY_NAME = "device_name"
    private const val KEY_ADDRESS = "device_address"

    data class Pending(val deviceName: String, val deviceAddress: String)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun save(context: Context, deviceName: String, deviceAddress: String) {
        prefs(context).edit()
            .putString(KEY_NAME, deviceName)
            .putString(KEY_ADDRESS, deviceAddress)
            .apply()
    }

    fun load(context: Context): Pending? {
        val p = prefs(context)
        val address = p.getString(KEY_ADDRESS, null) ?: return null
        return Pending(p.getString(KEY_NAME, null) ?: address, address)
    }

    /** 用户主动停止时必须调用，否则系统重启服务后会自己又开始录。 */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
