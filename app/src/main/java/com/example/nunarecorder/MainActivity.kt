package com.example.nunarecorder

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

import com.example.nunarecorder.audio.OpusToWavConverter
import com.example.nunarecorder.audio.AudioMetaUtil
   import com.example.nunarecorder.data.UserSettingsStorage
import com.example.nunarecorder.ble.HandshakeClient
import com.example.nunarecorder.ble.BleAudioReassembler
import com.example.nunarecorder.ble.ProtoConfig
import com.example.nunarecorder.data.DeviceStorage
import com.example.nunarecorder.data.PairedDevice
import com.example.nunarecorder.data.ScannedDevice
import com.example.nunarecorder.ui.components.BottomNavBar
import com.example.nunarecorder.ui.screen.MainScreen
import com.example.nunarecorder.ui.MainViewModel
import com.example.nunarecorder.ui.screen.RecordingsScreen
import com.example.nunarecorder.ui.screen.SettingsScreen
import com.example.wearable.TranscriptionProvider
import com.example.wearable.WearableConnectionConfig
import com.example.wearable.impl.NunaWearableServiceImpl
import com.example.wearable.internal.WearableBleConfig
import com.example.nunarecorder.ui.theme.NunaRecorderTheme
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException



class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    companion object {
        private const val TAG = "NunaRecorder"
        private const val CONTEXT_DATA_SUFFIX = ".bin"

        private val SERVICE_UUID: UUID get() = UUID.fromString(ProtoConfig.Service.SERVICE_UUID)
        private val CHAR_UUID: UUID get() = UUID.fromString(ProtoConfig.Service.RECORDING_CHAR_UUID)
        private val CCCD_UUID: UUID get() = UUID.fromString(ProtoConfig.Service.CCCD_UUID)
    }

    // 记录当前选中的设备 MAC 地址（来自列表点击）
    private var selectedDeviceAddress: String? = null

    // 当前连接设备名（用于文件命名）
    private var currentDeviceName: String = "unknown"

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false

    private var gatt: BluetoothGatt? = null
    private var recording = false

    // 握手客户端
    private lateinit var handshakeClient: HandshakeClient
    // 记录 A002 的通知是否已经成功开启
    private var isTransferNotificationEnabled = false
    // 点击"连接+握手"时设为 true，通知使能成功后会自动触发握手
    private var autoHandshakeOnConnect = false

    // DEBUG_WEARABLE_START — 与主流程 GATT 独立；若主界面已连同一设备请先断开再测
    private var wearableDebugService: NunaWearableServiceImpl? = null
    // DEBUG_WEARABLE_END

    // 统计接收到的音频数据
    private var totalPacketCount = 0L
    private var totalBytesCount = 0L

    private fun appendLog(msg: String) {
        Log.d(TAG, msg)
        viewModel.appendLog(msg)
    }

    // 重组器（把 BLE 数据重新组装成纯 Opus 流）
    private var opusReassembler: BleAudioReassembler? = null
    private var contextDataFile: File? = null
    private var contextDataOutput: FileOutputStream? = null
    private val contextDataLock = Any()
    private var contextDataCollecting = false
    private var sensorManager: SensorManager? = null
    private var locationManager: LocationManager? = null
    private var sensorListener: SensorEventListener? = null
    private var locationListener: LocationListener? = null

    private val httpClient by lazy { OkHttpClient() }


    // 持久化的已配对设备存储
    private lateinit var deviceStorage: DeviceStorage

    // 用户设置存储
    private lateinit var userSettingsStorage: UserSettingsStorage

    // 当前选中的“已配对设备”
    private var selectedPairedDevice: PairedDevice? = null


    // 权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        appendLog("Permission result: $perms")
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 允许内容延伸到状态栏/导航栏区域，由 Scaffold + WindowInsets 负责安全边距
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        isTransferNotificationEnabled = false

        handshakeClient = HandshakeClient(this) { msg ->
            appendLog(msg)
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        deviceStorage = DeviceStorage(this)
        userSettingsStorage = UserSettingsStorage(this)

        // 初始化已配对设备列表
        refreshPairedDeviceList()

        // 加载用户设置
        val initialSettings = userSettingsStorage.load()
        viewModel.setUserSettings(initialSettings)

        requestBlePermissions()

        setContent {
            NunaRecorderTheme {
                // 0 设备 1 录音 2 设置（Wearable 调试页已从导航移除，代码见 DEBUG_WEARABLE 注释块）
                var selectedTab by remember { mutableStateOf(0) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        BottomNavBar(
                            selectedTab = selectedTab,
                            onTabSelected = { selectedTab = it }
                        )
                    }
                ) { innerPadding ->
                    // innerPadding 已包含状态栏 + 导航栏 + 刘海/打孔屏的安全区
                    when (selectedTab) {
                        0 -> MainScreen(
                            logText = viewModel.logText.value,
                            targetName = viewModel.targetName.value,
                            onTargetNameChange = { viewModel.setTargetName(it) },
                            deviceList = viewModel.deviceList,
                            pairedDevices = viewModel.pairedDevices,
                            connectionStatus = viewModel.connectionStatus.value,
                            onDeviceClick = { scanned ->
                                val name = scanned.name ?: ""
                                viewModel.setTargetName(name)
                                selectedDeviceAddress = scanned.address
                                appendLog("Selected device from list: $name (${scanned.address})")
                            },
                            onPairedDeviceClick = { pd ->
                                selectedPairedDevice = pd
                                selectedDeviceAddress = pd.address
                                viewModel.setTargetName(pd.name ?: "")
                                appendLog("Selected paired device: ${pd.name} (${pd.address})")
                            },
                            onScanClick = { startScanForList() },
                            onConnectClick = { startConnectFlow() },
                            onStartRecordingClick = { startRecordingOnly() },
                            onStopRecordingClick = { stopRecordingFlow() },
                            onHandshakeClick = { performHandshake() },
                            modifier = Modifier.padding(innerPadding)
                        )
                        1 -> RecordingsScreen(
                            onPlayFile = { file, onProgress -> openOpusWithExternalPlayer(file, onProgress) },
                            onShareFile = { file -> shareFile(file) },
                            onShareFileWithContext = { file -> shareFileWithContext(file) },
                            onDeleteFile = { file, onDeleted -> deleteRecordingFile(file, onDeleted) },
                            onUploadFile = { file -> uploadRecording(file) },
                            onUploadFileWithContext = { file -> uploadRecordingWithContext(file) },
                            modifier = Modifier.padding(innerPadding)
                        )
                        2 -> SettingsScreen(
                            userSettings = viewModel.userSettings.value,
                            onUserIdChange = { newId ->
                                val current = viewModel.userSettings.value
                                viewModel.setUserSettings(current.copy(userId = newId))
                            },
                            onMacChange = { newMac ->
                                val current = viewModel.userSettings.value
                                viewModel.setUserSettings(current.copy(mac = newMac))
                            },
                            onServerHostChange = { newHost ->
                                val current = viewModel.userSettings.value
                                viewModel.setUserSettings(current.copy(serverHost = newHost))
                            },
                            onServerPortChange = { newPortStr ->
                                val port = newPortStr.toIntOrNull() ?: viewModel.userSettings.value.serverPort
                                val current = viewModel.userSettings.value
                                viewModel.setUserSettings(current.copy(serverPort = port))
                            },
                            onSave = {
                                userSettingsStorage.save(viewModel.userSettings.value)
                                appendLog("User settings saved.")
                            },
                            modifier = Modifier.padding(innerPadding)
                        )
                        // DEBUG_WEARABLE_START — 已从主导航移除；恢复时取消注释并 import WearableDebugScreen，同时在 BottomNavBar 恢复第 4 项
                        /*
                        3 -> WearableDebugScreen(
                            viewModel = viewModel,
                            deviceAddress = WearableBleConfig.DEFAULT_DEVICE_ADDRESS,
                            onStartRecording = { wearableDebugStartRecording() },
                            onStopRecording = { wearableDebugStopRecording() },
                            onQueryIsRecording = { wearableDebugQueryIsRecording() },
                            onSetListenerAndStartChunk = { wearableDebugSetListenerAndStartChunk() },
                            onStopChunkDelivery = { wearableDebugStopChunkDelivery() },
                            onStartTranscription = { wearableDebugStartTranscription() },
                            onStopTranscription = { wearableDebugStopTranscription() },
                            onClearLog = { viewModel.clearWearableDebugLog() },
                            modifier = Modifier.padding(innerPadding)
                        )
                        */
                        // DEBUG_WEARABLE_END
                    }
                }
            }
        }
    }

    private fun requestBlePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        // 位置权限：所有 Android 版本都需要（BLE scan + GPS 数据采集）
        needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    // ----------------- 高层流程 -----------------

    /**
     * 点击"Scan Devices"时调用：
     * 只是扫描并显示所有设备，不自动连接。
     */
    private fun startScanForList() {
        viewModel.clearDeviceList()
        appendLog("Start scanning for nearby BLE devices...")
        startScan(targetNameFilter = null)
    }

    /**
     * 点击"Connect"时调用：
     * 按输入框里的设备名 scan + 连接，但不立即握手或录制。
     */
    private fun startConnectFlow() {
        stopScan()
        autoHandshakeOnConnect = true   // 连接后自动触发握手

        val name = viewModel.targetName.value.trim()
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            appendLog("Bluetooth not enabled")
            return
        }

        val addr = selectedDeviceAddress
        if (!addr.isNullOrEmpty()) {
            appendLog("[1/4] 开始连接设备: $addr (name hint='$name')")
            val device = bluetoothAdapter!!.getRemoteDevice(addr)
            window.decorView.postDelayed({ connectToDevice(device) }, 300)
            return
        }

        if (name.isEmpty()) {
            appendLog("请先输入设备名称或从列表中选择一台设备。")
            return
        }

        appendLog("[1/4] 扫描目标设备: $name")
        startScan(targetNameFilter = name)
    }

    /**
     * 点击"Handshake"时调用：
     * 在已连接且 A002 通知已开启的情况下，执行握手流程（包含 settime）
     */
    private fun performHandshake() {
        val g = gatt
        if (g == null) {
            appendLog("Handshake: gatt is null, please Connect first.")
            return
        }
        if (!isTransferNotificationEnabled) {
            appendLog("Handshake: TRANSFER (A002) notifications not enabled yet.")
            return
        }
        appendLog("Handshake button clicked, starting handshake...")
        handshakeClient.startHandshake(g)
    }

    private fun resetStreamingStats() {
        totalPacketCount = 0
        totalBytesCount = 0
    }

    /**
     * 点击"Start Recording"时调用：
     * 在已经连接的情况下，对指定 service/char 开启 notify 并开始写文件。
     */
    private fun startRecordingOnly() {
        if (gatt == null) {
            appendLog("Not connected to any device, please Connect first.")
            return
        }
        if (recording) {
            appendLog("Already recording.")
            return
        }
        resetStreamingStats()
        appendLog("Starting recording on connected device...")

        val g = gatt ?: return
        val service = g.getService(SERVICE_UUID)
        if (service == null) {
            appendLog("Service $SERVICE_UUID not found")
            return
        }
        val characteristic = service.getCharacteristic(CHAR_UUID)
        if (characteristic == null) {
            appendLog("Characteristic $CHAR_UUID not found")
            return
        }
        appendLog("Target characteristic found, enabling notifications...")
        enableNotifications(g, characteristic)
    }

    /**
     * 点击"Stop Recording"时调用：停止 notify+断开+关文件。
     */
    private fun stopRecordingFlow() {
        appendLog("Stopping recording...")
        appendLog("Total received: packets=$totalPacketCount, bytes=$totalBytesCount")

        recording = false
        // _recordingState.value = false

        // 关闭重组器（完成最后 flush）
        opusReassembler?.close()
        opusReassembler = null
        stopContextDataCapture()
        com.example.nunarecorder.service.ContextDataService.stop(this)

        stopScan()
        stopNotifyAndDisconnect()
    }

    // ----------------- 扫描逻辑 -----------------

    /**
     * targetNameFilter:
     * - 为 null：扫到什么都加到列表
     * - 为非空字符串：只要匹配该名称就停止扫描并连接
     */
    private fun startScan(targetNameFilter: String?) {
        if (scanning) {
            appendLog("Scan already in progress, ignore.")
            return
        }

        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_SCAN
        else
            Manifest.permission.ACCESS_FINE_LOCATION

        if (ActivityCompat.checkSelfPermission(this, perm)
            != PackageManager.PERMISSION_GRANTED
        ) {
            appendLog("No scan permission, requesting...")
            requestBlePermissions()
            return
        }

        val filters = if (targetNameFilter != null) {
            listOf(
                ScanFilter.Builder()
                    .setDeviceName(targetNameFilter)
                    .build()
            )
        } else {
            emptyList<ScanFilter>()
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        currentTargetNameFilter = targetNameFilter
        bluetoothLeScanner?.startScan(filters, settings, scanCallback)
        scanning = true

        appendLog(
            if (targetNameFilter == null)
                "Scanning started (no name filter)."
            else
                "Scanning started (filter name = '$targetNameFilter')."
        )
    }


    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return

        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_SCAN
        else
            Manifest.permission.ACCESS_FINE_LOCATION

        if (ActivityCompat.checkSelfPermission(this, perm)
            != PackageManager.PERMISSION_GRANTED
        ) {
            appendLog("stopScan: no permission, skip.")
            return
        }

        bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
        appendLog("Scanning stopped.")
    }

    // 为了在 scanCallback 中知道当前是否有名字过滤
    private var currentTargetNameFilter: String? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.device?.let { device ->
                val name = device.name
                val address = device.address

                if (name.isNullOrEmpty()) return

                viewModel.addDeviceIfAbsent(ScannedDevice(name = name, address = address))

                val filterName = currentTargetNameFilter
                if (!filterName.isNullOrEmpty() && name == filterName) {
                    appendLog("Found target device: $name ($address), stop scan and connect.")
                    stopScan()
                    connectToDevice(device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            appendLog("Scan failed: error=$errorCode")
        }
    }


    // ----------------- 连接 & GATT 回调 -----------------

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_CONNECT
        else
            Manifest.permission.BLUETOOTH

        if (ActivityCompat.checkSelfPermission(this, perm)
            != PackageManager.PERMISSION_GRANTED
        ) {
            appendLog("Connect: no BLUETOOTH_CONNECT permission.")
            requestBlePermissions()
            return
        }

        appendLog("Connecting to ${device.name ?: "(no name)"} (${device.address})...")

        // 关闭之前的 GATT
        gatt?.let {
            try {
                it.disconnect()
                it.close()
            } catch (e: Exception) {
                // 不再详细输出错误信息
            }
        }

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, gattCallback)
        }

        appendLog("Connect request sent, waiting for response...")
    }


    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            super.onConnectionStateChange(gatt, status, newState)

            if (status != BluetoothGatt.GATT_SUCCESS) {
                when (status) {
                    4 -> appendLog("Connection error (4: GATT_FAILURE).")
                    133 -> appendLog("Connection error (133: internal error).")
                    else -> appendLog("Connection error: status=$status")
                }
                appendLog("Closing GATT due to error.")
                try {
                    gatt.disconnect()
                    gatt.close()
                } catch (_: Exception) {}
                viewModel.setConnectionStatus("Disconnected (error)")
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val dev = gatt.device
                currentDeviceName = dev.name ?: dev.address ?: "unknown"
                appendLog("[2/4] GATT 已连接: ${dev.name ?: "(no name)"} (${dev.address})，正在发现服务...")

                val paired = PairedDevice(
                    name = dev.name,
                    address = dev.address,
                    lastConnectedTime = System.currentTimeMillis()
                )
                deviceStorage.saveOrUpdateDevice(paired)
                refreshPairedDeviceList()

                viewModel.setConnectionStatus("Connected: ${dev.name ?: "(no name)"} (${dev.address})")

                val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    Manifest.permission.BLUETOOTH_CONNECT
                else
                    Manifest.permission.BLUETOOTH
                if (ActivityCompat.checkSelfPermission(this@MainActivity, perm)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    appendLog("discoverServices: no permission.")
                    return
                }
                val ok = gatt.discoverServices()
                if (!ok) {
                    appendLog("Service discovery start failed.")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                appendLog("Disconnected from device.")
                recording = false
                isTransferNotificationEnabled = false

                opusReassembler?.close()
                opusReassembler = null
                stopContextDataCapture()
                com.example.nunarecorder.service.ContextDataService.stop(this@MainActivity)
                // closeFile()
                viewModel.setConnectionStatus("Disconnected")
            }
        }


        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appendLog("服务发现失败: status=$status")
                return
            }
            appendLog("[3/4] 服务发现成功，正在启用 A002 通知...")
            enableTransferNotifications(gatt)
        }


        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            super.onCharacteristicChanged(gatt, characteristic, value)

            // 先让握手模块处理（只关心 A002）
            handshakeClient.onNotification(gatt, characteristic)

            // 录音逻辑：只处理 A003（不打印数据）
            if (characteristic.uuid == CHAR_UUID) {
                writeToFile(value)
                totalPacketCount++
                totalBytesCount += value.size
                // 不再每秒打印统计信息，只在停止时给出总量
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)

            if (descriptor.uuid == CCCD_UUID &&
                descriptor.characteristic.uuid.toString()
                    .equals(ProtoConfig.Service.TRANSFER_CHAR_UUID, ignoreCase = true)
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    isTransferNotificationEnabled = true
                    appendLog("[4/4] A002 通知已启用。")
                    if (autoHandshakeOnConnect) {
                        autoHandshakeOnConnect = false
                        appendLog("[4/4] 自动握手：延时 800 ms 后执行握手...")
                        runOnUiThread {
                            window.decorView.postDelayed({ performHandshake() }, 800)
                        }
                    } else {
                        appendLog("A002 通知就绪，可手动执行握手。")
                    }
                } else {
                    appendLog("启用 A002 通知失败: status=$status")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_CONNECT
        else
            Manifest.permission.BLUETOOTH

        if (ActivityCompat.checkSelfPermission(this, perm)
            != PackageManager.PERMISSION_GRANTED
        ) {
            appendLog("Start recording: no permission to enable notifications.")
            requestBlePermissions()
            return
        }

        gatt.setCharacteristicNotification(characteristic, true)

        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (cccd != null) {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val success = gatt.writeDescriptor(cccd)

            if (success) {
                openFileForRecording()
                recording = true
                appendLog("Recording started: listening to audio stream...")
            } else {
                appendLog("Failed to enable recording notifications.")
            }
        } else {
            appendLog("Recording CCCD not found, cannot enable notifications.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableTransferNotifications(gatt: BluetoothGatt) {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_CONNECT
        else
            Manifest.permission.BLUETOOTH

        if (ActivityCompat.checkSelfPermission(this, perm)
            != PackageManager.PERMISSION_GRANTED
        ) {
            appendLog("enableTransferNotifications: no permission.")
            requestBlePermissions()
            return
        }

        val service = gatt.getService(SERVICE_UUID)
        if (service == null) {
            appendLog("TRANSFER service (A000) not found.")
            return
        }

        val transferChar = service.getCharacteristic(UUID.fromString(ProtoConfig.Service.TRANSFER_CHAR_UUID))
        if (transferChar == null) {
            appendLog("TRANSFER char (A002) not found.")
            return
        }

        gatt.setCharacteristicNotification(transferChar, true)

        val cccd = transferChar.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            appendLog("TRANSFER CCCD not found.")
            return
        }

        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val okWrite = gatt.writeDescriptor(cccd)
        if (!okWrite) {
            appendLog("Failed to write TRANSFER CCCD.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopNotifyAndDisconnect() {
        try {
            val g = gatt ?: return
            appendLog("Disconnecting GATT...")
            g.disconnect()
            g.close()
            gatt = null
            recording = false
            isTransferNotificationEnabled = false
        } catch (e: Exception) {
            appendLog("Error while disconnecting: ${e.message}")
        }
    }

    // ----------------- 文件写入 -----------------

    private fun openFileForRecording() {
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }
            // 设备名做一下简单清洗，避免空格、特殊字符
            val safeDeviceName = currentDeviceName
                .replace("[^a-zA-Z0-9_-]".toRegex(), "_")
                .ifBlank { "unknown" }

            val timestamp = System.currentTimeMillis()
            val fileName = "nuna_${safeDeviceName}_$timestamp.opus"

            val file = File(downloadDir, fileName)
            // 直接保存为 .opus
            //val file = File(downloadDir, "audio_stream_${System.currentTimeMillis()}.opus")

            // 如果之前有旧的 reassembler，先关掉
            opusReassembler?.close()

            opusReassembler = BleAudioReassembler(file) { msg ->
                appendLog(msg)
            }

            appendLog("Opus output file opened: ${file.absolutePath}")
            // 启动前台服务采集 GPS + IMU，确保熄屏下也能持续记录
            com.example.nunarecorder.service.ContextDataService.start(this, file)
            appendLog("Context data service started.")
        } catch (e: Exception) {
            appendLog("Failed to open opus file: ${e.message}")
        }
    }

    private fun startContextDataCapture(audioFile: File) {
        stopContextDataCapture()
        try {
            val sidecar = File(
                audioFile.parentFile,
                "${audioFile.name.substringBeforeLast(".")}$CONTEXT_DATA_SUFFIX"
            )
            contextDataFile = sidecar
            contextDataOutput = FileOutputStream(sidecar, false)
            contextDataCollecting = true
            writeContextRecord("""{"type":"meta","audio_file":"${audioFile.name}","started_at_ms":${System.currentTimeMillis()}}""")

            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

            val imu = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (!contextDataCollecting) return
                    val sensorType = when (event.sensor.type) {
                        Sensor.TYPE_ACCELEROMETER -> "accel"
                        Sensor.TYPE_GYROSCOPE -> "gyro"
                        Sensor.TYPE_MAGNETIC_FIELD -> "mag"
                        else -> return
                    }
                    val tMs = System.currentTimeMillis()
                    writeContextRecord(
                        """{"type":"imu","sensor":"$sensorType","t_ms":$tMs,"x":${event.values.getOrNull(0) ?: 0f},"y":${event.values.getOrNull(1) ?: 0f},"z":${event.values.getOrNull(2) ?: 0f}}"""
                    )
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensorListener = imu
            sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
                sensorManager?.registerListener(imu, it, SensorManager.SENSOR_DELAY_GAME)
            }
            sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.also {
                sensorManager?.registerListener(imu, it, SensorManager.SENSOR_DELAY_GAME)
            }
            sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also {
                sensorManager?.registerListener(imu, it, SensorManager.SENSOR_DELAY_GAME)
            }

            val hasFine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            appendLog("Context capture: FINE=$hasFine COARSE=$hasCoarse")
            if (hasFine || hasCoarse) {
                val loc = LocationListener { location: Location ->
                    if (!contextDataCollecting) return@LocationListener
                    val tMs = System.currentTimeMillis()
                    writeContextRecord(
                        """{"type":"gps","provider":"${location.provider}","t_ms":$tMs,"lat":${location.latitude},"lon":${location.longitude},"alt":${location.altitude},"acc":${location.accuracy},"speed":${location.speed},"bearing":${location.bearing}}"""
                    )
                    appendLog("GPS fix: provider=${location.provider} lat=${location.latitude} lon=${location.longitude} acc=${location.accuracy}m")
                }
                locationListener = loc
                val gpsOk = runCatching {
                    locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, loc, Looper.getMainLooper())
                }
                val netOk = runCatching {
                    locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, loc, Looper.getMainLooper())
                }
                appendLog("Context capture: GPS provider registered=${gpsOk.isSuccess}, NETWORK provider registered=${netOk.isSuccess}")
            } else {
                appendLog("Context capture: 无位置权限，仅记录 IMU 数据。请在系统设置中为本 App 授予位置权限。")
            }
            appendLog("Context data file opened: ${sidecar.absolutePath}")
        } catch (e: Exception) {
            appendLog("Failed to start context capture: ${e.message}")
            stopContextDataCapture()
        }
    }

    private fun writeContextRecord(line: String) {
        synchronized(contextDataLock) {
            if (!contextDataCollecting) return
            try {
                contextDataOutput?.write((line + "\n").toByteArray(Charsets.UTF_8))
            } catch (_: Exception) {}
        }
    }

    private fun stopContextDataCapture() {
        contextDataCollecting = false
        try {
            locationListener?.let { locationManager?.removeUpdates(it) }
        } catch (_: Exception) {}
        locationListener = null
        try {
            sensorListener?.let { sensorManager?.unregisterListener(it) }
        } catch (_: Exception) {}
        sensorListener = null
        synchronized(contextDataLock) {
            try {
                contextDataOutput?.flush()
                contextDataOutput?.close()
            } catch (_: Exception) {}
            contextDataOutput = null
        }
        contextDataFile = null
    }

    private fun writeToFile(data: ByteArray) {
        opusReassembler?.feed(data)
    }

    private fun openOpusWithExternalPlayer(
        file: File,
        onProgress: (Int, Int) -> Unit
    ) {
        appendLog("Play request for opus: ${file.name}, size=${file.length()}")

        val wavFile = OpusToWavConverter.getWavFileForOpus(file)
        if (wavFile.exists()) {
            appendLog("WAV already exists, will play: ${wavFile.name}, size=${wavFile.length()}")
            // 已有 WAV，直接视为 100%
            onProgress(1, 1)
            playWavFile(wavFile)
            return
        }

        lifecycleScope.launch {
            try {
                appendLog("Converting opus to WAV: ${file.name}")
                val result = withContext(Dispatchers.IO) {
                    OpusToWavConverter.ensureWavFile(file, onProgress)
                }
                appendLog("Conversion success. WAV=${result.name}, size=${result.length()}")
                playWavFile(result)
            } catch (e: Exception) {
                Log.e(TAG, "Opus to WAV conversion failed", e)
                appendLog("Failed to convert opus to WAV: ${e.message}")
            }
        }
    }

    private var mediaPlayer: MediaPlayer? = null

    private fun uploadRecording(file: File) {
        lifecycleScope.launch {
            appendLog("Uploading ${file.name} ...")
            try {
                withContext(Dispatchers.IO) {
                    doUploadToServer(file)
                }
                appendLog("Upload success: ${file.name}")
            } catch (e: Exception) {
                appendLog("Upload failed: ${e.message}")
                Log.e(TAG, "Upload failed", e)
            }
        }
    }

    private fun doUploadToServer(file: File) {
        val settings = viewModel.userSettings.value
        val userId = settings.userId.ifBlank { "mock-user-001" }
        val mac = settings.mac.ifBlank { "AA:BB:CC:DD:EE:FF" }
        val startTimeMs = AudioMetaUtil.parseStartTimeFromFileName(file.name) ?: 0L
        val durationMs = AudioMetaUtil.computeDurationMsForOpusFile(file)
        val endTimeMs = startTimeMs + durationMs

        val metadataJson = JSONObject().apply {
            put("userId", userId)
            put("name", file.name)
            put("startTime", startTimeMs)
            put("endTime", endTimeMs)
            put("mac", mac)
            put("size", file.length())
        }.toString()

        val mediaTypeAudio = "audio/ogg".toMediaType()
        val mediaTypeText = "text/plain".toMediaType()

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody(mediaTypeAudio)
            )
            .addFormDataPart(
                "metadata",
                "metadata.json",
                metadataJson.toRequestBody(mediaTypeText)
            )
            .build()

        val baseUrl = "http://${settings.serverHost}:${settings.serverPort}"
        val request = Request.Builder()
            .url("$baseUrl/thingx/api/file/upload/audio")
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected response: ${response.code} ${response.message}")
            }
        }
    }

    private fun playWavFile(wavFile: File) {
        try {
            appendLog("Preparing MediaPlayer for WAV: ${wavFile.name}, size=${wavFile.length()}")

            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(wavFile.absolutePath)
                setOnPreparedListener {
                    appendLog("MediaPlayer prepared, start playback: ${wavFile.name}")
                    start()
                }
                setOnCompletionListener {
                    appendLog("MediaPlayer completed: ${wavFile.name}")
                    release()
                    this@MainActivity.mediaPlayer = null
                }
                setOnErrorListener { _, what, extra ->
                    appendLog("MediaPlayer error: what=$what, extra=$extra for file=${wavFile.name}")
                    release()
                    this@MainActivity.mediaPlayer = null
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Play WAV failed", e)
            appendLog("Failed to play WAV: ${e.message}")
        }
    }

    private fun shareFile(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "audio/ogg"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "分享录音"))
        } catch (e: Exception) {
            appendLog("分享失败: ${e.message}")
        }
    }

    private fun shareFileWithContext(file: File) {
        try {
            val sidecar = File(file.parentFile, "${file.nameWithoutExtension}$CONTEXT_DATA_SUFFIX")
            val uris = ArrayList<android.net.Uri>()
            uris.add(androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", file))
            if (sidecar.exists()) {
                uris.add(androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", sidecar))
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "分享录音及上下文数据"))
        } catch (e: Exception) {
            appendLog("分享（含上下文）失败: ${e.message}")
        }
    }

    private fun uploadRecordingWithContext(file: File) {
        val sidecar = File(file.parentFile, "${file.nameWithoutExtension}$CONTEXT_DATA_SUFFIX")
        appendLog("上传: ${file.name}" + if (sidecar.exists()) " + ${sidecar.name}" else "")
        uploadRecording(file)
        // Sidecar upload can be extended similarly to uploadRecording
        if (sidecar.exists()) {
            appendLog("上传上下文数据文件 ${sidecar.name}（占位，与音频上传相同接口）")
            uploadRecording(sidecar)
        }
    }

    private fun deleteRecordingFile(file: File, onDeleted: () -> Unit) {
        try {
            val base = file.nameWithoutExtension
            val dir = file.parentFile
            // Delete all files sharing the same base name regardless of extension
            val deleted = mutableListOf<String>()
            dir?.listFiles { f -> f.isFile && f.nameWithoutExtension == base }?.forEach { f ->
                if (f.delete()) deleted.add(f.name)
            }
            if (deleted.isEmpty()) {
                appendLog("文件不存在: ${file.name}")
            } else {
                appendLog("已删除: ${deleted.joinToString(", ")}")
            }
            onDeleted()
        } catch (e: Exception) {
            appendLog("删除文件时出错: ${e.message}")
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
        stopRecordingFlow()
        // DEBUG_WEARABLE_START
        wearableDebugService?.stopRecording()
        wearableDebugService = null
        // DEBUG_WEARABLE_END
    }

    // DEBUG_WEARABLE_START
    private fun appendWearableDebugLog(msg: String) {
        runOnUiThread {
            viewModel.appendWearableDebugLog(msg)
            Log.d(NunaWearableServiceImpl.DEBUG_TAG, "[UI] $msg")
        }
    }

    private fun ensureWearableDebugService(): NunaWearableServiceImpl {
        if (wearableDebugService == null) {
            val impl = NunaWearableServiceImpl(applicationContext)
            impl.setConnectionConfig(
                WearableConnectionConfig(
                    deviceAddress = WearableBleConfig.DEFAULT_DEVICE_ADDRESS,
                    verificationCode = "123456",
                    dumpOverlapWavToDebugDir = true
                    // deepgramApiKey 使用 WearableConnectionConfig 中的默认值
                )
            )
            wearableDebugService = impl
            appendWearableDebugLog("Service created, address=${WearableBleConfig.DEFAULT_DEVICE_ADDRESS}")
        }
        return wearableDebugService!!
    }

    private fun wearableDebugStartRecording() {
        if (gatt != null) {
            appendWearableDebugLog("Warning: MainActivity gatt is connected; disconnect first to avoid conflict.")
        }
        val svc = ensureWearableDebugService()
        appendWearableDebugLog("startRecording invoked")
        svc.startRecording(
            onReady = {
                appendWearableDebugLog("onReady (A003 subscribed)")
            },
            onError = { msg ->
                appendWearableDebugLog("onError: $msg")
            }
        )
    }

    private fun wearableDebugStopRecording() {
        wearableDebugService?.let { svc ->
            appendWearableDebugLog("stopRecording invoked")
            svc.stopRecording()
        } ?: appendWearableDebugLog("stopRecording: service was null")
        wearableDebugService = null
    }

    private fun wearableDebugQueryIsRecording() {
        val svc = wearableDebugService
        if (svc == null) {
            appendWearableDebugLog("isRecording: service null -> skip")
            return
        }
        val v = svc.isRecording()
        appendWearableDebugLog("isRecording -> $v (see also Logcat WearableDebug)")
    }

    /**
     * 调试：10s 块 / 2s 重叠；WAV 仅由 NunaWearableServiceImpl 在 dumpOverlapWavToDebugDir=true 时写入 overlap_*.wav。
     */
    private fun wearableDebugSetListenerAndStartChunk() {
        val svc = ensureWearableDebugService()
        val chunkMs = 10_000L
        val overlapMs = 2_000L
        (svc as? NunaWearableServiceImpl)?.setDumpOverlapWavToDebugDir(true)
        appendWearableDebugLog("setListener + startChunkDelivery(${chunkMs}ms, overlap=${overlapMs}) overlap WAV dump ON -> wearable_wav_debug/")
        var chunkIndex = 0
        svc.setListener { chunk ->
            chunkIndex++
            appendWearableDebugLog("onAudioChunkReady #$chunkIndex wavBytes=${chunk.data.size} start=${chunk.startTimeMs} end=${chunk.endTimeMs}")
        }
        svc.startChunkDelivery(chunkDurationMs = chunkMs, overlapDurationMs = overlapMs)
    }

    private fun wearableDebugStopChunkDelivery() {
        wearableDebugService?.let { svc ->
            appendWearableDebugLog("stopChunkDelivery invoked")
            svc.stopChunkDelivery()
        } ?: appendWearableDebugLog("stopChunkDelivery: service null")
    }

    /** 转写测试：使用 WearableConnectionConfig 中的 Deepgram API Key 并 startTranscription，转写结果持续写入调试日志和 Logcat。 */
    private fun wearableDebugStartTranscription() {
        val svc = ensureWearableDebugService()
        val cfgKey = kotlin.runCatching {
            (svc as? NunaWearableServiceImpl)
                ?.let { implField ->
                    // 通过公開的 connectionConfig 间接读取 Deepgram API Key
                    // 实际上 NunaWearableServiceImpl 已在 ensureWearableDebugService 中配置好 WearableConnectionConfig
                    null
                }
        }.getOrNull()
        (svc as? TranscriptionProvider)?.let { tp ->
            tp.setListener { text, startMs, endMs ->
                val msg = "onTranscriptionReady: \"$text\" [${startMs}ms - ${endMs}ms]"
                appendWearableDebugLog(msg)
                Log.d(NunaWearableServiceImpl.DEBUG_TAG, "[Transcription] $msg")
            }
            tp.startTranscription()
            appendWearableDebugLog("Start 转写: Deepgram listen-flux 已连接，结果将持续打印到本页日志与 Logcat (WearableDebug)")
            Log.d(NunaWearableServiceImpl.DEBUG_TAG, "[UI] Start 转写: Deepgram listen-flux 已连接")
        } ?: run {
            appendWearableDebugLog("Start 转写: service 未实现 TranscriptionProvider")
        }
    }

    private fun wearableDebugStopTranscription() {
        (wearableDebugService as? TranscriptionProvider)?.let { tp ->
            tp.stopTranscription()
            appendWearableDebugLog("stopTranscription invoked")
            Log.d(NunaWearableServiceImpl.DEBUG_TAG, "[UI] stopTranscription invoked")
        } ?: appendWearableDebugLog("stopTranscription: service null 或未实现 TranscriptionProvider")
    }
    // DEBUG_WEARABLE_END
    private fun refreshPairedDeviceList() {
        viewModel.setPairedDevices(deviceStorage.getPairedDevices())
    }
}