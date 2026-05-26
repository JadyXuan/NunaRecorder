package com.example.nunarecorder

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.nunarecorder.audio.SegmentPlaybackState
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.util.UUID

import com.example.nunarecorder.audio.SegmentAudioPlayer
import com.example.nunarecorder.data.UserSettingsStorage
import com.example.nunarecorder.ble.HandshakeClient
import com.example.nunarecorder.data.RecordingEntry
import com.example.nunarecorder.migration.MigrationCoordinator
import com.example.nunarecorder.sync.SessionSyncCoordinator
import com.example.nunarecorder.recording.SessionRecorder
import com.example.nunarecorder.session.SessionPaths
import com.example.nunarecorder.vad.VadJobQueue
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
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    companion object {
        private const val TAG = "NunaRecorder"

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

    private val sessionRecorder = SessionRecorder { appendLog(it) }
    private val segmentPlayer = SegmentAudioPlayer { appendLog(it) }

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
        VadJobQueue.start(this)
        val resumed = VadJobQueue.resumeAllIncompleteSessions()
        if (resumed > 0) appendLog("自动续传 VAD: $resumed 个音频段待分析")
        MigrationCoordinator.onLog = { appendLog(it) }
        SessionSyncCoordinator.onLog = { appendLog(it) }

        setContent {
            NunaRecorderTheme {
                // 0 设备 1 录音 2 设置（Wearable 调试页已从导航移除，代码见 DEBUG_WEARABLE 注释块）
                var selectedTab by remember { mutableStateOf(0) }
                var segmentPlayback by remember { mutableStateOf<SegmentPlaybackState?>(null) }

                DisposableEffect(Unit) {
                    segmentPlayer.setOnStateChanged { segmentPlayback = it }
                    onDispose { segmentPlayer.stop() }
                }

                // 主内容在上、底栏在下，边界对齐，避免主界面盖住导航按钮
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
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
                                modifier = Modifier.fillMaxSize()
                            )
                        1 -> RecordingsScreen(
                            segmentPlayback = segmentPlayback,
                            onPlaySegment = { session, index, relPath ->
                                segmentPlayer.play(
                                    lifecycleScope,
                                    session.dir,
                                    index,
                                    File(session.dir, relPath)
                                )
                            },
                            onStopPlayback = { segmentPlayer.stop() },
                            onShareEntry = { entry, withContext, withVad -> shareRecordingEntry(entry, withContext, withVad) },
                            onDeleteEntry = { entry, onDeleted -> deleteRecordingEntry(entry, onDeleted) },
                            onUploadEntry = { entry, withContext, withVad -> uploadRecordingEntry(entry, withContext, withVad) },
                            onMigrateLegacy = { opus -> MigrationCoordinator.start(this@MainActivity, opus) },
                            modifier = Modifier.fillMaxSize()
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
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    BottomNavBar(
                        selectedTab = selectedTab,
                        onTabSelected = { selectedTab = it },
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                    )
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            needed.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
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
        sessionRecorder.stop()
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

                sessionRecorder.stop()
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
            val addr = gatt?.device?.address ?: selectedDeviceAddress
            sessionRecorder.start(currentDeviceName, addr)
            val dir = sessionRecorder.activeSessionDir
            if (dir != null) {
                com.example.nunarecorder.service.ContextDataService.start(this, dir)
                appendLog("Session + context capture started: ${dir.name}")
            }
        } catch (e: Exception) {
            appendLog("Failed to start session recording: ${e.message}")
        }
    }

    private fun writeToFile(data: ByteArray) {
        sessionRecorder.feed(data)
    }

    private fun shareRecordingEntry(entry: RecordingEntry, withContext: Boolean, withVad: Boolean) {
        val files = collectEntryFiles(entry, withContext, withVad)
        if (files.isEmpty()) {
            appendLog("没有可分享的文件")
            return
        }
        try {
            val uris = ArrayList(files.map {
                androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", it)
            })
            val intent = if (uris.size == 1) {
                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "*/*"
                    putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            startActivity(android.content.Intent.createChooser(intent, "分享录音"))
        } catch (e: Exception) {
            appendLog("分享失败: ${e.message}")
        }
    }

    private fun uploadRecordingEntry(entry: RecordingEntry, withContext: Boolean, withVad: Boolean) {
        appendLog("开始同步: ${entry.displayName}")
        SessionSyncCoordinator.start(
            entry = entry,
            includeContext = withContext,
            includeVad = withVad,
            settings = viewModel.userSettings.value,
            httpClient = httpClient
        )
    }

    private fun collectEntryFiles(
        entry: RecordingEntry,
        withContext: Boolean,
        withVad: Boolean
    ): List<File> {
        return when (entry) {
            is RecordingEntry.Session -> {
                val list = mutableListOf<File>()
                list.add(SessionPaths.manifestFile(entry.dir))
                entry.manifest.segments.forEach { list.add(File(entry.dir, it.file)) }
                if (withContext) {
                    val ctx = SessionPaths.contextFile(entry.dir)
                    if (ctx.exists()) list.add(ctx)
                }
                if (withVad) {
                    val vad = SessionPaths.vadPrelabelFile(entry.dir)
                    if (vad.exists()) list.add(vad)
                }
                list.filter { it.exists() }
            }
            is RecordingEntry.LegacyOpus -> {
                val list = mutableListOf(entry.opusFile)
                if (withContext) {
                    val bin = File(entry.opusFile.parentFile, "${entry.opusFile.nameWithoutExtension}${SessionPaths.LEGACY_BIN_SUFFIX}")
                    if (bin.exists()) list.add(bin)
                }
                list.filter { it.exists() }
            }
        }
    }

    private fun deleteRecordingEntry(entry: RecordingEntry, onDeleted: () -> Unit) {
        try {
            when (entry) {
                is RecordingEntry.Session -> {
                    val name = entry.dir.name
                    if (entry.dir.deleteRecursively()) {
                        appendLog("已删除会话: $name")
                    } else {
                        appendLog("删除会话失败: $name")
                    }
                }
                is RecordingEntry.LegacyOpus -> {
                    val base = entry.opusFile.nameWithoutExtension
                    val dir = entry.opusFile.parentFile
                    val deleted = mutableListOf<String>()
                    dir?.listFiles { f -> f.isFile && f.nameWithoutExtension == base }?.forEach { f ->
                        if (f.delete()) deleted.add(f.name)
                    }
                    appendLog("已删除: ${deleted.joinToString(", ")}")
                }
            }
            onDeleted()
        } catch (e: Exception) {
            appendLog("删除时出错: ${e.message}")
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        segmentPlayer.stop()
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