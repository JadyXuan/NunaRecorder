package com.example.nunarecorder.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.example.nunarecorder.data.LogLevel
import com.example.nunarecorder.data.PairedDevice
import com.example.nunarecorder.data.ScannedDevice
import com.example.nunarecorder.data.UserSettings
import com.example.nunarecorder.sync.ServerHandshakeCheck
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面 UI 状态，与 BLE/录音逻辑解耦。
 */
class MainViewModel : ViewModel() {

    companion object {
        private const val MAX_LOG_LINES = 120
        private const val WEARABLE_DEBUG_MAX_LINES = 250
    }

    val logText = mutableStateOf("")
    val deviceList = mutableStateListOf<ScannedDevice>()
    val pairedDevices = mutableStateListOf<PairedDevice>()
    val connectionStatus = mutableStateOf("未连接")
    val userSettings = mutableStateOf(UserSettings())
    val selectedDeviceAddress = mutableStateOf<String?>(null)
    /** 正在录制的会话目录绝对路径（供录音列表实时刷新） */
    val activeRecordingPath = mutableStateOf<String?>(null)
    /** 内存中的实时统计（比 manifest 刷新更及时） */
    val liveRecordingStats = mutableStateOf<LiveRecordingUiStats?>(null)

    /** 保存设置后的服务器自检结果 */
    val settingsCheckResult = mutableStateOf<ServerHandshakeCheck.Result?>(null)
    val settingsCheckRunning = mutableStateOf(false)

    val wearableDebugLogLines = mutableStateListOf<String>()

    fun appendWearableDebugLog(msg: String) {
        val line = "${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())} $msg"
        wearableDebugLogLines.add(line)
        while (wearableDebugLogLines.size > WEARABLE_DEBUG_MAX_LINES) {
            wearableDebugLogLines.removeAt(0)
        }
    }

    fun clearWearableDebugLog() {
        wearableDebugLogLines.clear()
    }

    fun appendLog(msg: String, level: LogLevel = LogLevel.INFO) {
        if (!userSettings.value.logLevel.allows(level)) return
        val prefix = when (level) {
            LogLevel.INFO -> ""
            LogLevel.DEBUG -> "[调试] "
        }
        val line = "${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())} $prefix$msg"
        val existing = logText.value
        val combined = if (existing.isEmpty()) line else "$existing\n$line"
        val lines = combined.split('\n')
        logText.value = if (lines.size > MAX_LOG_LINES) {
            lines.takeLast(MAX_LOG_LINES).joinToString("\n")
        } else {
            combined
        }
    }

    fun setConnectionStatus(status: String) {
        connectionStatus.value = status
    }

    fun selectDevice(address: String?) {
        selectedDeviceAddress.value = address
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

    fun setActiveRecordingPath(path: String?) {
        activeRecordingPath.value = path
        if (path == null) {
            liveRecordingStats.value = null
        }
    }

    fun updateLiveRecordingStats(stats: LiveRecordingUiStats?) {
        liveRecordingStats.value = stats
    }
}
