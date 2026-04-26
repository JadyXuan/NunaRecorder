package com.example.nunarecorder.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.example.nunarecorder.data.PairedDevice
import com.example.nunarecorder.data.ScannedDevice
import com.example.nunarecorder.data.UserSettings

/**
 * 主界面 UI 状态，与 BLE/录音逻辑解耦。
 * 由 MainActivity 在 BLE 回调和用户操作时更新。
 */
class MainViewModel : ViewModel() {

    companion object {
        const val DEFAULT_TARGET_NAME = "nuna device_01AF"
        private const val WEARABLE_DEBUG_MAX_LINES = 250
    }

    val logText = mutableStateOf("Log...\n")
    val targetName = mutableStateOf(DEFAULT_TARGET_NAME)
    val deviceList = mutableStateListOf<ScannedDevice>()
    val pairedDevices = mutableStateListOf<PairedDevice>()
    val connectionStatus = mutableStateOf("Not connected")
    val userSettings = mutableStateOf(UserSettings())

    /**
     * Wearable 调试页专用日志（与 appendLog / NunaRecorder 主日志分离）。
     * 可随时整段删除 DEBUG_WEARABLE 相关代码。
     */
    val wearableDebugLogLines = mutableStateListOf<String>()

    /** 须在主线程调用，以便 Compose 能订阅 mutableStateListOf 变化 */
    fun appendWearableDebugLog(msg: String) {
        val line = "${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} $msg"
        wearableDebugLogLines.add(line)
        while (wearableDebugLogLines.size > WEARABLE_DEBUG_MAX_LINES) {
            wearableDebugLogLines.removeAt(0)
        }
    }

    fun clearWearableDebugLog() {
        wearableDebugLogLines.clear()
    }

    fun appendLog(msg: String) {
        logText.value = logText.value + msg + "\n"
    }

    fun setTargetName(name: String) {
        targetName.value = name
    }

    fun setConnectionStatus(status: String) {
        connectionStatus.value = status
    }

    fun clearDeviceList() {
        deviceList.clear()
    }

    fun addDeviceIfAbsent(device: ScannedDevice) {
        if (deviceList.none { it.address == device.address }) {
            deviceList.add(device)
        }
    }

    fun setPairedDevices(devices: List<PairedDevice>) {
        pairedDevices.clear()
        pairedDevices.addAll(devices)
    }

    fun setUserSettings(newSettings: UserSettings) {
        userSettings.value = newSettings
    }
}
