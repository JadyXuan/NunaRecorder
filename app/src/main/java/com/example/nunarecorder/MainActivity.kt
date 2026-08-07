package com.example.nunarecorder

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.nunarecorder.audio.SegmentPlaybackState
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.util.ArrayDeque
import java.util.UUID

import com.example.nunarecorder.audio.SegmentAudioPlayer
import com.example.nunarecorder.data.UserSettingsStorage
import com.example.nunarecorder.ble.HandshakeClient
import com.example.nunarecorder.ble.HandshakeEvent
import com.example.nunarecorder.ble.BatteryTelemetry
import com.example.nunarecorder.ble.DevicePowerState
import com.example.nunarecorder.connection.RecorderConnectionEvent
import com.example.nunarecorder.connection.RecorderConnectionPhase
import com.example.nunarecorder.data.RecordingEntry
import com.example.nunarecorder.data.LogLevel
import com.example.nunarecorder.migration.MigrationCoordinator
import com.example.nunarecorder.recording.RecordingOptions
import com.example.nunarecorder.sync.SessionSyncCoordinator
import com.example.nunarecorder.recording.SessionRecorder
import com.example.nunarecorder.session.SessionPaths
import com.example.nunarecorder.vad.VadJobQueue
import com.example.nunarecorder.ble.ProtoConfig
import com.example.nunarecorder.data.DeviceStorage
import com.example.nunarecorder.data.PairedDevice
import com.example.nunarecorder.data.ScannedDevice
import com.example.nunarecorder.diagnostics.AudioStallEvent
import com.example.nunarecorder.diagnostics.AudioStallEventType
import com.example.nunarecorder.diagnostics.AudioStallMonitor
import com.example.nunarecorder.ui.components.BottomNavBar
import com.example.nunarecorder.ui.screen.MainScreen
import com.example.nunarecorder.ui.screen.LifelogScreen
import com.example.nunarecorder.ui.MainViewModel
import com.example.nunarecorder.ui.LiveRecordingUiStats
import com.example.nunarecorder.ui.screen.RecordingsScreen
import com.example.nunarecorder.ui.screen.SettingsScreen
import com.example.nunarecorder.lifelog.LifelogCoordinator
import com.example.nunarecorder.lifelog.LifelogNotifications
import com.example.nunarecorder.lifelog.LifelogPollWorker
import com.example.nunarecorder.sync.SessionAutoUploadWorker
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

    // 记录当前选中的设备 MAC 地址（来自列表点击，存于 ViewModel）

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

    // 可选电量能力：XIAO 的 A004 优先，标准 BAS 作为原 Nuna 回退。
    private val batteryReadCandidates = ArrayDeque<BluetoothGattCharacteristic>()
    private var activeBatteryReadUuid: UUID? = null
    private var batteryNotificationUuid: UUID? = null
    private var batterySetupCompleted = false
    private var batteryReadTimeout: Runnable? = null

    // DEBUG_WEARABLE_START — 与主流程 GATT 独立；若主界面已连同一设备请先断开再测
    private var wearableDebugService: NunaWearableServiceImpl? = null
    // DEBUG_WEARABLE_END

    // 统计接收到的音频数据
    private var totalPacketCount = 0L
    private var totalBytesCount = 0L
    private var lastStatsUiUpdateMs = 0L
    @Volatile private var lastAudioPacketElapsedMs = 0L
    @Volatile private var lastAudioFrameId: Int? = null
    @Volatile private var hasReceivedAudioPacket = false
    @Volatile private var activityVisible = false

    private val diagnosticLogger get() = (application as NunaApplication).diagnosticLogger
    private val audioStallMonitor = AudioStallMonitor()
    private val diagnosticHandler = Handler(Looper.getMainLooper())
    private var diagnosticTickerRunning = false
    private var lastDiagnosticStatsMs = 0L
    private var lastDiagnosticRssiRequestMs = 0L

    private val diagnosticTicker = object : Runnable {
        override fun run() {
            if (!diagnosticTickerRunning || !recording) return
            val now = SystemClock.elapsedRealtime()
            audioStallMonitor.check(now)?.let(::handleAudioStallEvent)

            if (now - lastDiagnosticStatsMs >= 5_000L) {
                lastDiagnosticStatsMs = now
                diagnosticLogger.log("audio_stats", recordingDiagnosticFields(now))
            }
            if (now - lastDiagnosticRssiRequestMs >= 30_000L) {
                lastDiagnosticRssiRequestMs = now
                requestDiagnosticRssi()
            }
            diagnosticHandler.postDelayed(this, 1_000L)
        }
    }

    private fun appendLog(msg: String, level: LogLevel = LogLevel.INFO) {
        Log.d(TAG, msg)
        diagnosticLogger.log(
            "ui_log",
            mapOf("level" to level.name.lowercase(), "message" to msg)
        )
        runOnUiThread { viewModel.appendLog(msg, level) }
    }

    private fun appendDebug(msg: String) = appendLog(msg, LogLevel.DEBUG)

    private fun transitionConnectionNow(event: RecorderConnectionEvent): Boolean {
        val accepted = viewModel.transitionConnection(event)
        diagnosticLogger.log(
            "recorder_state_transition",
            mapOf(
                "event" to event.javaClass.simpleName,
                "accepted" to accepted,
                "phase" to viewModel.connectionState.value.phase.name
            )
        )
        return accepted
    }

    private fun transitionConnection(event: RecorderConnectionEvent) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            transitionConnectionNow(event)
        } else {
            runOnUiThread { transitionConnectionNow(event) }
        }
    }

    private val sessionRecorder = SessionRecorder { appendDebug(it) }
    private val segmentPlayer = SegmentAudioPlayer { appendDebug(it) }

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
        diagnosticLogger.log("activity_created", mapOf("saved_state" to (savedInstanceState != null)))
        // 允许内容延伸到状态栏/导航栏区域，由 Scaffold + WindowInsets 负责安全边距
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        isTransferNotificationEnabled = false

        handshakeClient = HandshakeClient(
            context = this,
            log = ::appendDebug,
            onEvent = { event ->
                when (event) {
                    HandshakeEvent.Started -> Unit
                    HandshakeEvent.VerificationAccepted -> appendDebug("设备验证码已确认，正在同步时间…")
                    HandshakeEvent.Ready -> {
                        transitionConnection(RecorderConnectionEvent.HandshakeSucceeded)
                        appendLog("握手成功，设备已就绪")
                    }
                    is HandshakeEvent.Failed -> {
                        transitionConnection(RecorderConnectionEvent.HandshakeFailed(event.reason))
                        appendLog("握手失败：${event.reason}")
                    }
                }
            }
        )

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
        LifelogCoordinator.configure(httpClient, initialSettings)
        LifelogCoordinator.onLog = { appendLog(it) }
        if (intent.getBooleanExtra(LifelogNotifications.EXTRA_OPEN_LIFELOG, false)) {
            viewModel.selectTab(2)
        }

        requestBlePermissions()
        VadJobQueue.start(this)
        val resumed = VadJobQueue.resumeAllIncompleteSessions()
        if (resumed > 0) appendLog("自动续传 VAD: $resumed 个音频段待分析")
        MigrationCoordinator.onLog = { appendLog(it) }
        SessionSyncCoordinator.onLog = { appendLog(it) }

        setContent {
            val logText by viewModel.logText
            val selectedDeviceAddress by viewModel.selectedDeviceAddress
            val connectionState by viewModel.connectionState
            val devicePowerState by viewModel.devicePowerState
            val activeRecordingPath by viewModel.activeRecordingPath
            val liveRecordingStats by viewModel.liveRecordingStats
            val userSettings by viewModel.userSettings
            val selectedTab by viewModel.selectedTab
            val lifelogState by LifelogCoordinator.state.collectAsState()

            NunaRecorderTheme {
                // 0 设备 1 录音 2 生活 3 设置
                var segmentPlayback by remember { mutableStateOf<SegmentPlaybackState?>(null) }

                DisposableEffect(Unit) {
                    segmentPlayer.setOnStateChanged { segmentPlayback = it }
                    onDispose { segmentPlayer.stop() }
                }
                LaunchedEffect(selectedTab) {
                    if (selectedTab == 2) LifelogCoordinator.refresh()
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
                                logText = logText,
                                deviceList = viewModel.deviceList,
                                pairedDevices = viewModel.pairedDevices,
                                selectedDeviceAddress = selectedDeviceAddress,
                                connectionState = connectionState,
                                devicePowerState = devicePowerState,
                                liveRecordingStats = liveRecordingStats,
                                onDeviceClick = { scanned ->
                                    viewModel.selectDevice(scanned.address)
                                    appendLog("已选择: ${scanned.name ?: scanned.address}")
                                },
                                onPairedDeviceClick = { pd ->
                                    selectedPairedDevice = pd
                                    viewModel.selectDevice(pd.address)
                                    appendLog("已选择: ${pd.name ?: pd.address}")
                                },
                                onScanClick = { startScanForList() },
                                onConnectClick = { startConnectFlow() },
                                onStartRecordingClick = { startRecordingOnly() },
                                onStopRecordingClick = { stopRecordingFlow() },
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
                            onMigrateLegacy = { opus, options ->
                                MigrationCoordinator.start(this@MainActivity, opus, options)
                            },
                            activeRecordingPath = activeRecordingPath,
                            liveRecordingStats = liveRecordingStats,
                            modifier = Modifier.fillMaxSize()
                        )
                            2 -> LifelogScreen(
                                state = lifelogState,
                                onRefresh = { LifelogCoordinator.refresh() },
                                onPreviousDay = { LifelogCoordinator.previousDay() },
                                onNextDay = { LifelogCoordinator.nextDay() },
                                onAnnotate = { prompt, action, label ->
                                    LifelogCoordinator.annotate(prompt, action, label)
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                            3 -> SettingsScreen(
                                userSettings = userSettings,
                                onUserIdChange = { newId ->
                                    viewModel.setUserSettings(userSettings.copy(userId = newId))
                                },
                                onBaseUrlChange = { newBaseUrl ->
                                    viewModel.setUserSettings(userSettings.copy(baseUrl = newBaseUrl))
                                },
                                onBasicAuthUsernameChange = { username ->
                                    viewModel.setUserSettings(
                                        userSettings.copy(basicAuthUsername = username)
                                    )
                                },
                                onBasicAuthPasswordChange = { password ->
                                    viewModel.setUserSettings(
                                        userSettings.copy(basicAuthPassword = password)
                                    )
                                },
                                onLogLevelChange = { level ->
                                    viewModel.setUserSettings(userSettings.copy(logLevel = level))
                                },
                                onAutoVadChange = { enabled ->
                                    viewModel.setUserSettings(userSettings.copy(autoVadOnRecord = enabled))
                                },
                                onSegmentEnabledChange = { enabled ->
                                    viewModel.setUserSettings(userSettings.copy(segmentEnabled = enabled))
                                },
                                onSegmentDurationChange = { secStr ->
                                    val sec = secStr.toIntOrNull()?.coerceIn(10, 600)
                                        ?: userSettings.segmentDurationSec
                                    viewModel.setUserSettings(userSettings.copy(segmentDurationSec = sec))
                                },
                                onLifelogEnabledChange = { enabled ->
                                    viewModel.setUserSettings(userSettings.copy(lifelogEnabled = enabled))
                                },
                                onAnnotationPollingChange = { enabled ->
                                    viewModel.setUserSettings(
                                        userSettings.copy(annotationPollingEnabled = enabled)
                                    )
                                },
                                onAutoUploadChange = { enabled ->
                                    viewModel.setUserSettings(
                                        userSettings.copy(autoUploadEnabled = enabled)
                                    )
                                },
                                onAutoUploadWifiOnlyChange = { enabled ->
                                    viewModel.setUserSettings(
                                        userSettings.copy(autoUploadWifiOnly = enabled)
                                    )
                                },
                                onShareDiagnostics = { shareDiagnosticLogs() },
                                onSave = {
                                    userSettingsStorage.save(userSettings)
                                    LifelogCoordinator.configure(httpClient, userSettings)
                                    LifelogPollWorker.schedule(
                                        this@MainActivity,
                                        enabled = userSettings.lifelogEnabled &&
                                            userSettings.annotationPollingEnabled &&
                                            userSettings.serverConfigurationError() == null
                                    )
                                    SessionAutoUploadWorker.schedule(this@MainActivity, userSettings)
                                    val serverError = userSettings.serverConfigurationError()
                                    appendLog(
                                        if (serverError == null) "设置已保存"
                                        else "设置已保存；服务器未启用：$serverError"
                                    )
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    BottomNavBar(
                        selectedTab = selectedTab,
                        onTabSelected = { viewModel.selectTab(it) },
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        activityVisible = true
        diagnosticLogger.log("activity_visible", mapOf("visible" to true))
    }

    override fun onStop() {
        activityVisible = false
        diagnosticLogger.log("activity_visible", mapOf("visible" to false, "recording" to recording))
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(LifelogNotifications.EXTRA_OPEN_LIFELOG, false)) {
            viewModel.selectTab(2)
            LifelogCoordinator.refresh()
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
        appendLog("开始扫描附近设备…")
        startScan(targetNameFilter = null)
    }

    private fun startConnectFlow() {
        stopScan()

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            appendLog("请先开启蓝牙")
            return
        }

        val addr = viewModel.selectedDeviceAddress.value
        if (addr.isNullOrEmpty()) {
            appendLog("请先从列表点选要连接的设备")
            return
        }

        if (!transitionConnectionNow(RecorderConnectionEvent.ConnectRequested(addr))) {
            appendLog("当前状态下不能重复连接")
            return
        }
        resetBatteryTelemetry(DevicePowerState.reading())
        autoHandshakeOnConnect = true
        appendLog("正在连接 $addr …")
        val device = try {
            bluetoothAdapter!!.getRemoteDevice(addr)
        } catch (e: IllegalArgumentException) {
            transitionConnectionNow(RecorderConnectionEvent.ConnectionFailed("设备地址无效"))
            appendLog("设备地址无效")
            return
        }
        window.decorView.postDelayed({ connectToDevice(device) }, 300)
    }

    /**
     * 点击"Handshake"时调用：
     * 在已连接且 A002 通知已开启的情况下，执行握手流程（包含 settime）
     */
    private fun performHandshake() {
        val g = gatt ?: run {
            appendLog("未连接设备，请先连接")
            transitionConnectionNow(RecorderConnectionEvent.ConnectionFailed("BLE 连接已丢失"))
            return
        }
        if (!isTransferNotificationEnabled) {
            appendDebug("握手: A002 通知尚未就绪")
            transitionConnectionNow(RecorderConnectionEvent.HandshakeFailed("握手通知通道未就绪"))
            return
        }
        if (!transitionConnectionNow(RecorderConnectionEvent.HandshakeStarted)) {
            appendDebug("握手: 当前状态不允许启动握手")
            return
        }
        appendLog("握手中…")
        handshakeClient.startHandshake(g)
    }

    private fun resetStreamingStats() {
        totalPacketCount = 0
        totalBytesCount = 0
        lastAudioPacketElapsedMs = 0L
        lastAudioFrameId = null
        hasReceivedAudioPacket = false
        lastDiagnosticStatsMs = 0L
        lastDiagnosticRssiRequestMs = 0L
    }

    private fun startRecordingDiagnostics() {
        val now = SystemClock.elapsedRealtime()
        lastAudioPacketElapsedMs = now
        audioStallMonitor.start(now)
        diagnosticTickerRunning = true
        diagnosticHandler.removeCallbacks(diagnosticTicker)
        diagnosticHandler.post(diagnosticTicker)
        diagnosticLogger.log("recording_monitor_started", recordingDiagnosticFields(now))
    }

    private fun stopRecordingDiagnostics(reason: String) {
        if (!diagnosticTickerRunning) return
        val now = SystemClock.elapsedRealtime()
        diagnosticLogger.log(
            "recording_monitor_stopped",
            recordingDiagnosticFields(now) + ("reason" to reason)
        )
        diagnosticTickerRunning = false
        diagnosticHandler.removeCallbacks(diagnosticTicker)
        audioStallMonitor.stop()
    }

    private fun recordingDiagnosticFields(now: Long): Map<String, Any?> = mapOf(
        "session_id" to sessionRecorder.activeSessionDir?.name,
        "device_address" to (gatt?.device?.address ?: viewModel.selectedDeviceAddress.value),
        "activity_visible" to activityVisible,
        "packet_count" to totalPacketCount,
        "byte_count" to totalBytesCount,
        "last_frame_id" to lastAudioFrameId,
        "received_any_audio" to hasReceivedAudioPacket,
        "last_audio_age_ms" to if (lastAudioPacketElapsedMs > 0L) {
            (now - lastAudioPacketElapsedMs).coerceAtLeast(0L)
        } else null
    )

    private fun handleAudioStallEvent(event: AudioStallEvent) {
        val eventName = when (event.type) {
            AudioStallEventType.WARNING -> "audio_silence_warning"
            AudioStallEventType.STALLED -> "audio_stalled"
            AudioStallEventType.RECOVERED -> "audio_recovered"
        }
        diagnosticLogger.log(
            eventName,
            recordingDiagnosticFields(SystemClock.elapsedRealtime()) +
                ("silence_ms" to event.silenceMs)
        )
        when (event.type) {
            AudioStallEventType.WARNING -> Unit
            AudioStallEventType.STALLED -> {
                appendLog("音频流已停滞 ${event.silenceMs / 1000.0} 秒，BLE 尚未报告断开")
                transitionConnection(RecorderConnectionEvent.AudioStalled)
            }
            AudioStallEventType.RECOVERED -> {
                appendLog("音频流已恢复（中断 ${event.silenceMs / 1000.0} 秒）")
                transitionConnection(RecorderConnectionEvent.AudioRecovered)
                updateLiveRecordingStatsUi()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestDiagnosticRssi() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
        if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            diagnosticLogger.log("rssi_request_skipped", mapOf("reason" to "permission"))
            return
        }
        val accepted = gatt?.readRemoteRssi() ?: false
        if (!accepted) diagnosticLogger.log("rssi_request_rejected")
    }

    /**
     * 点击"Start Recording"时调用：
     * 在已经连接的情况下，对指定 service/char 开启 notify 并开始写文件。
     */
    private fun startRecordingOnly() {
        if (!transitionConnectionNow(RecorderConnectionEvent.RecordingStartRequested)) {
            appendLog("设备尚未完成握手，暂时不能开始录制")
            return
        }
        if (gatt == null) {
            transitionConnectionNow(RecorderConnectionEvent.ConnectionFailed("BLE 连接已丢失"))
            appendLog("BLE 连接已丢失，请重新连接")
            return
        }
        resetStreamingStats()
        appendLog("准备开始录制…")

        val g = gatt ?: return
        val service = g.getService(SERVICE_UUID)
        if (service == null) {
            transitionConnectionNow(RecorderConnectionEvent.RecordingStartFailed("未找到音频服务"))
            appendLog("未找到音频服务，连接可能未完成")
            return
        }
        val characteristic = service.getCharacteristic(CHAR_UUID)
        if (characteristic == null) {
            transitionConnectionNow(RecorderConnectionEvent.RecordingStartFailed("未找到音频特征"))
            appendLog("未找到音频特征")
            return
        }
        appendDebug("启用 A003 音频通知…")
        enableNotifications(g, characteristic)
    }

    private fun updateLiveRecordingStatsUi() {
        if (!recording) return
        val now = System.currentTimeMillis()
        if (now - lastStatsUiUpdateMs < 400L) return
        lastStatsUiUpdateMs = now
        val stats = sessionRecorder.liveStats() ?: return
        viewModel.updateLiveRecordingStats(
            LiveRecordingUiStats(
                sessionPath = stats.sessionDir.absolutePath,
                totalBytes = stats.totalBytes,
                closedSegmentCount = stats.closedSegmentCount,
                openSegmentBytes = stats.openSegmentBytes,
                blePacketCount = totalPacketCount
            )
        )
    }

    private fun stopRecordingFlow() {
        transitionConnectionNow(RecorderConnectionEvent.StopRequested)
        appendLog("停止录制（共 ${totalPacketCount} 包 · ${formatBytes(totalBytesCount)}）")

        stopRecordingDiagnostics("user_or_activity_stop")
        recording = false
        sessionRecorder.stop()
        viewModel.setActiveRecordingPath(null)
        com.example.nunarecorder.service.ContextDataService.stop(this)

        stopScan()
        stopNotifyAndDisconnect()
    }

    private fun formatBytes(n: Long): String = when {
        n >= 1_048_576 -> "%.1f MB".format(n / 1_048_576.0)
        n >= 1024 -> "%.1f KB".format(n / 1024.0)
        else -> "$n B"
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
            if (targetNameFilter == null) "扫描中…" else "扫描目标设备…"
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
        appendDebug("扫描已停止")
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
                    appendLog("找到设备 $name，正在连接…")
                    stopScan()
                    connectToDevice(device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            appendLog("扫描失败 (code=$errorCode)")
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
            transitionConnection(RecorderConnectionEvent.ConnectionFailed("缺少蓝牙连接权限"))
            requestBlePermissions()
            return
        }

        appendDebug("正在连接 ${device.name ?: device.address}…")
        diagnosticLogger.log(
            "gatt_connect_requested",
            mapOf("device_name" to device.name, "device_address" to device.address)
        )

        // 关闭之前的 GATT
        val previousGatt = gatt
        gatt = null
        previousGatt?.let {
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

        appendDebug("连接请求已发送")
    }


    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            super.onConnectionStateChange(gatt, status, newState)

            val callbackNow = SystemClock.elapsedRealtime()
            diagnosticLogger.log(
                "gatt_state_change",
                recordingDiagnosticFields(callbackNow) + mapOf(
                    "status" to status,
                    "new_state" to newState,
                    "callback_device_address" to gatt.device.address
                )
            )

            if (gatt !== this@MainActivity.gatt) {
                diagnosticLogger.log(
                    "stale_gatt_callback_ignored",
                    mapOf("status" to status, "new_state" to newState)
                )
                try {
                    gatt.close()
                } catch (_: Exception) {}
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                appendLog("连接失败 (status=$status)")
                if (recording || sessionRecorder.isRecording) {
                    appendLog("BLE 异常断开，当前录音会话已封口")
                }
                stopRecordingDiagnostics("gatt_error_$status")
                resetBatteryTelemetry(null)
                recording = false
                isTransferNotificationEnabled = false
                sessionRecorder.stop()
                viewModel.setActiveRecordingPath(null)
                com.example.nunarecorder.service.ContextDataService.stop(this@MainActivity)
                this@MainActivity.gatt = null
                appendDebug("关闭 GATT")
                try {
                    gatt.disconnect()
                    gatt.close()
                } catch (_: Exception) {}
                transitionConnection(RecorderConnectionEvent.ConnectionFailed("BLE 连接失败 (status=$status)"))
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val dev = gatt.device
                currentDeviceName = dev.name ?: dev.address ?: "unknown"
                appendLog("已连接 ${dev.name ?: dev.address}")

                val paired = PairedDevice(
                    name = dev.name,
                    address = dev.address,
                    lastConnectedTime = System.currentTimeMillis()
                )
                deviceStorage.saveOrUpdateDevice(paired)
                refreshPairedDeviceList()

                transitionConnection(
                    RecorderConnectionEvent.GattConnected(dev.name ?: dev.address)
                )

                val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    Manifest.permission.BLUETOOTH_CONNECT
                else
                    Manifest.permission.BLUETOOTH
                if (ActivityCompat.checkSelfPermission(this@MainActivity, perm)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    appendDebug("discoverServices: 无权限")
                    transitionConnection(RecorderConnectionEvent.ConnectionFailed("缺少蓝牙连接权限"))
                    return
                }
                val ok = gatt.discoverServices()
                if (!ok) {
                    appendLog("服务发现启动失败")
                    transitionConnection(RecorderConnectionEvent.ConnectionFailed("无法启动服务发现"))
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                appendLog("已断开连接")
                stopRecordingDiagnostics("gatt_disconnected")
                recording = false
                isTransferNotificationEnabled = false
                resetBatteryTelemetry(null)

                sessionRecorder.stop()
                viewModel.setActiveRecordingPath(null)
                com.example.nunarecorder.service.ContextDataService.stop(this@MainActivity)
                this@MainActivity.gatt = null
                transitionConnection(RecorderConnectionEvent.Disconnected)
            }
        }


        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            diagnosticLogger.log(
                "gatt_services_discovered",
                mapOf("status" to status, "service_count" to gatt.services.size)
            )
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appendLog("服务发现失败 (status=$status)")
                transitionConnection(RecorderConnectionEvent.ConnectionFailed("服务发现失败 (status=$status)"))
                return
            }
            prepareBatteryCandidates(gatt)
            transitionConnection(RecorderConnectionEvent.ServicesDiscovered)
            appendDebug("服务发现完成，启用 A002 通知…")
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

            BatteryTelemetry.parse(characteristic.uuid, value)?.let { state ->
                setDevicePowerState(state)
                logDevicePower("device_power_notification", state)
            }

            // 录音逻辑：只处理 A003（不打印数据）
            if (characteristic.uuid == CHAR_UUID) {
                val now = SystemClock.elapsedRealtime()
                totalPacketCount++
                totalBytesCount += value.size
                lastAudioPacketElapsedMs = now
                lastAudioFrameId = extractAudioFrameId(value) ?: lastAudioFrameId
                hasReceivedAudioPacket = true
                audioStallMonitor.onPacket(now)?.let(::handleAudioStallEvent)
                writeToFile(value)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleBatteryCharacteristicRead(gatt, characteristic, value, status)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            handleBatteryCharacteristicRead(
                gatt,
                characteristic,
                characteristic.value ?: byteArrayOf(),
                status
            )
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            diagnosticLogger.log(
                "gatt_descriptor_write",
                mapOf(
                    "status" to status,
                    "descriptor_uuid" to descriptor.uuid.toString(),
                    "characteristic_uuid" to descriptor.characteristic.uuid.toString()
                )
            )

            if (descriptor.uuid == CCCD_UUID &&
                descriptor.characteristic.uuid.toString()
                    .equals(ProtoConfig.Service.TRANSFER_CHAR_UUID, ignoreCase = true)
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    isTransferNotificationEnabled = true
                    transitionConnection(RecorderConnectionEvent.HandshakeNotificationsReady)
                    appendDebug("A002 通知已启用")
                    beginBatterySetup(gatt)
                } else {
                    appendLog("启用 A002 通知失败 (status=$status)")
                    transitionConnection(
                        RecorderConnectionEvent.HandshakeFailed("启用握手通知失败 (status=$status)")
                    )
                }
            }

            if (descriptor.uuid == CCCD_UUID &&
                descriptor.characteristic.uuid == batteryNotificationUuid
            ) {
                appendDebug(
                    if (status == BluetoothGatt.GATT_SUCCESS) "电量通知已启用"
                    else "电量通知启用失败 (status=$status)，保留单次读数"
                )
                finishBatterySetupAndHandshake()
            }

            if (descriptor.uuid == CCCD_UUID && descriptor.characteristic.uuid == CHAR_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS &&
                    viewModel.connectionState.value.phase == RecorderConnectionPhase.STARTING_RECORDING
                ) {
                    if (openFileForRecording()) {
                        recording = true
                        startRecordingDiagnostics()
                        transitionConnection(RecorderConnectionEvent.RecordingStarted)
                        appendLog("录制已开始")
                    } else {
                        gatt.setCharacteristicNotification(descriptor.characteristic, false)
                        transitionConnection(
                            RecorderConnectionEvent.RecordingStartFailed("无法创建录音会话")
                        )
                    }
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    gatt.setCharacteristicNotification(descriptor.characteristic, false)
                    appendLog("启用音频通知失败 (status=$status)")
                    transitionConnection(
                        RecorderConnectionEvent.RecordingStartFailed("启用音频通知失败 (status=$status)")
                    )
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            super.onReadRemoteRssi(gatt, rssi, status)
            diagnosticLogger.log("gatt_rssi", mapOf("status" to status, "rssi_dbm" to rssi))
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            diagnosticLogger.log("gatt_mtu", mapOf("status" to status, "mtu" to mtu))
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyUpdate(gatt, txPhy, rxPhy, status)
            diagnosticLogger.log(
                "gatt_phy",
                mapOf("status" to status, "tx_phy" to txPhy, "rx_phy" to rxPhy)
            )
        }

    }

    private fun setDevicePowerState(state: DevicePowerState?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            viewModel.setDevicePowerState(state)
        } else {
            runOnUiThread { viewModel.setDevicePowerState(state) }
        }
    }

    private fun logDevicePower(event: String, state: DevicePowerState) {
        diagnosticLogger.log(
            event,
            mapOf(
                "percent" to state.percent,
                "voltage_mv" to state.voltageMv,
                "usb_present" to state.usbPresent,
                "charging" to state.charging,
                "source" to state.source?.name
            )
        )
    }

    private fun resetBatteryTelemetry(state: DevicePowerState?) {
        batteryReadTimeout?.let(diagnosticHandler::removeCallbacks)
        batteryReadTimeout = null
        batteryReadCandidates.clear()
        activeBatteryReadUuid = null
        batteryNotificationUuid = null
        batterySetupCompleted = false
        setDevicePowerState(state)
    }

    private fun prepareBatteryCandidates(gatt: BluetoothGatt) {
        batteryReadCandidates.clear()
        activeBatteryReadUuid = null
        batteryNotificationUuid = null
        batterySetupCompleted = false

        // A004 only exists on the extended firmware. If absent or malformed,
        // the standard Battery Service keeps original Nuna devices compatible.
        gatt.getService(SERVICE_UUID)
            ?.getCharacteristic(BatteryTelemetry.NUNA_POWER_UUID)
            ?.let(batteryReadCandidates::addLast)
        gatt.getService(BatteryTelemetry.STANDARD_SERVICE_UUID)
            ?.getCharacteristic(BatteryTelemetry.STANDARD_LEVEL_UUID)
            ?.let(batteryReadCandidates::addLast)
    }

    @SuppressLint("MissingPermission")
    private fun beginBatterySetup(gatt: BluetoothGatt) {
        if (batterySetupCompleted) return
        readNextBatteryCandidate(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun readNextBatteryCandidate(gatt: BluetoothGatt) {
        batteryReadTimeout?.let(diagnosticHandler::removeCallbacks)
        batteryReadTimeout = null
        activeBatteryReadUuid = null

        val characteristic = batteryReadCandidates.pollFirst()
        if (characteristic == null) {
            setDevicePowerState(DevicePowerState.unsupported())
            appendDebug("设备未提供兼容的电量特征，继续原 Nuna 握手流程")
            finishBatterySetupAndHandshake()
            return
        }

        activeBatteryReadUuid = characteristic.uuid
        if (!gatt.readCharacteristic(characteristic)) {
            appendDebug("读取电量特征 ${characteristic.uuid} 未入队，尝试回退")
            activeBatteryReadUuid = null
            readNextBatteryCandidate(gatt)
            return
        }

        val expectedUuid = characteristic.uuid
        batteryReadTimeout = Runnable {
            if (gatt === this.gatt && activeBatteryReadUuid == expectedUuid) {
                appendDebug("读取电量特征超时，尝试回退")
                activeBatteryReadUuid = null
                readNextBatteryCandidate(gatt)
            }
        }.also { diagnosticHandler.postDelayed(it, 2_000L) }
    }

    private fun handleBatteryCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        if (gatt !== this.gatt || characteristic.uuid != activeBatteryReadUuid) return
        batteryReadTimeout?.let(diagnosticHandler::removeCallbacks)
        batteryReadTimeout = null
        activeBatteryReadUuid = null

        val state = if (status == BluetoothGatt.GATT_SUCCESS) {
            BatteryTelemetry.parse(characteristic.uuid, value)
        } else null
        if (state == null) {
            appendDebug("电量特征 ${characteristic.uuid} 不可用 (status=$status)，尝试回退")
            readNextBatteryCandidate(gatt)
            return
        }

        setDevicePowerState(state)
        logDevicePower("device_power_read", state)
        enableBatteryNotifications(gatt, characteristic)
    }

    @SuppressLint("MissingPermission")
    private fun enableBatteryNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        val supportsNotify = characteristic.properties and
            BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (!supportsNotify || cccd == null ||
            !gatt.setCharacteristicNotification(characteristic, true)
        ) {
            finishBatterySetupAndHandshake()
            return
        }

        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        batteryNotificationUuid = characteristic.uuid
        if (!gatt.writeDescriptor(cccd)) {
            batteryNotificationUuid = null
            gatt.setCharacteristicNotification(characteristic, false)
            appendDebug("电量通知配置未入队，保留单次读数")
            finishBatterySetupAndHandshake()
        }
    }

    private fun finishBatterySetupAndHandshake() {
        if (batterySetupCompleted) return
        batterySetupCompleted = true
        batteryReadTimeout?.let(diagnosticHandler::removeCallbacks)
        batteryReadTimeout = null
        activeBatteryReadUuid = null

        if (autoHandshakeOnConnect) {
            autoHandshakeOnConnect = false
            appendLog("自动握手中…")
            runOnUiThread {
                window.decorView.postDelayed({ performHandshake() }, 800)
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
            transitionConnection(RecorderConnectionEvent.RecordingStartFailed("缺少蓝牙连接权限"))
            requestBlePermissions()
            return
        }

        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            appendLog("无法在本机启用音频通知")
            transitionConnection(RecorderConnectionEvent.RecordingStartFailed("本机通知注册失败"))
            return
        }

        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (cccd != null) {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val success = gatt.writeDescriptor(cccd)

            if (success) {
                appendLog("正在启用音频通知…")
            } else {
                appendLog("无法启用音频通知")
                gatt.setCharacteristicNotification(characteristic, false)
                transitionConnection(RecorderConnectionEvent.RecordingStartFailed("设备拒绝通知配置"))
            }
        } else {
            appendLog("Recording CCCD not found, cannot enable notifications.")
            gatt.setCharacteristicNotification(characteristic, false)
            transitionConnection(RecorderConnectionEvent.RecordingStartFailed("音频通知描述符不存在"))
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
            transitionConnection(RecorderConnectionEvent.HandshakeFailed("缺少蓝牙连接权限"))
            requestBlePermissions()
            return
        }

        val service = gatt.getService(SERVICE_UUID)
        if (service == null) {
            appendLog("TRANSFER service (A000) not found.")
            transitionConnection(RecorderConnectionEvent.HandshakeFailed("未找到握手服务"))
            return
        }

        val transferChar = service.getCharacteristic(UUID.fromString(ProtoConfig.Service.TRANSFER_CHAR_UUID))
        if (transferChar == null) {
            appendLog("TRANSFER char (A002) not found.")
            transitionConnection(RecorderConnectionEvent.HandshakeFailed("未找到握手特征"))
            return
        }

        if (!gatt.setCharacteristicNotification(transferChar, true)) {
            appendLog("Failed to register TRANSFER notifications locally.")
            transitionConnection(RecorderConnectionEvent.HandshakeFailed("本机握手通知注册失败"))
            return
        }

        val cccd = transferChar.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            appendLog("TRANSFER CCCD not found.")
            transitionConnection(RecorderConnectionEvent.HandshakeFailed("握手通知描述符不存在"))
            return
        }

        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val okWrite = gatt.writeDescriptor(cccd)
        if (!okWrite) {
            appendLog("Failed to write TRANSFER CCCD.")
            gatt.setCharacteristicNotification(transferChar, false)
            transitionConnection(RecorderConnectionEvent.HandshakeFailed("设备拒绝握手通知配置"))
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopNotifyAndDisconnect() {
        try {
            val g = gatt ?: run {
                resetBatteryTelemetry(null)
                transitionConnection(RecorderConnectionEvent.Disconnected)
                return
            }
            appendLog("Disconnecting GATT...")
            g.disconnect()
            g.close()
            gatt = null
            recording = false
            isTransferNotificationEnabled = false
            resetBatteryTelemetry(null)
            transitionConnection(RecorderConnectionEvent.Disconnected)
        } catch (e: Exception) {
            appendLog("Error while disconnecting: ${e.message}")
            transitionConnection(RecorderConnectionEvent.ConnectionFailed("断开连接失败"))
        }
    }

    // ----------------- 文件写入 -----------------

    private fun openFileForRecording(): Boolean {
        return try {
            val addr = gatt?.device?.address ?: viewModel.selectedDeviceAddress.value
            val options = RecordingOptions.from(viewModel.userSettings.value)
            sessionRecorder.start(currentDeviceName, addr, options)
            val dir = sessionRecorder.activeSessionDir
            if (dir != null) {
                com.example.nunarecorder.service.ContextDataService.start(this, dir)
                viewModel.setActiveRecordingPath(dir.absolutePath)
                updateLiveRecordingStatsUi()
                val mode = buildString {
                    if (options.segmentEnabled) append("切片 ${options.segmentDurationMs / 1000}s")
                    else append("整段")
                    append(if (options.autoVadOnRecord) " · 自动VAD" else " · 无VAD")
                }
                appendLog("会话 ${dir.name} ($mode)")
                true
            } else {
                appendLog("开始录制失败: 无法创建会话目录")
                false
            }
        } catch (e: Exception) {
            appendLog("开始录制失败: ${e.message}")
            false
        }
    }

    private fun writeToFile(data: ByteArray) {
        val integrityOk = sessionRecorder.feed(data)
        updateLiveRecordingStatsUi()
        if (!integrityOk && recording) {
            appendLog("检测到 BLE 音频丢帧，本次会话已停止且不会自动上传")
            runOnUiThread {
                if (recording) stopRecordingFlow()
            }
        }
    }

    private fun extractAudioFrameId(data: ByteArray): Int? {
        if (data.size < 9 || (data[0].toInt() and 0xFF) != 0xAA ||
            (data[1].toInt() and 0xFF) != 0x10
        ) return null
        return (data[7].toInt() and 0xFF) or ((data[8].toInt() and 0xFF) shl 8)
    }

    private fun shareDiagnosticLogs() {
        diagnosticLogger.log("diagnostic_export_requested")
        diagnosticLogger.snapshotFiles { files ->
            runOnUiThread {
                if (files.isEmpty()) {
                    appendLog("暂无可分享的诊断日志")
                    return@runOnUiThread
                }
                try {
                    val uris = ArrayList(files.map {
                        androidx.core.content.FileProvider.getUriForFile(
                            this,
                            "${packageName}.fileprovider",
                            it
                        )
                    })
                    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "application/x-ndjson"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "分享 Nuna 诊断日志"))
                } catch (e: Exception) {
                    appendLog("分享诊断日志失败: ${e.message}")
                }
            }
        }
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
        diagnosticLogger.log("activity_destroyed", mapOf("recording" to recording))
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
                    dumpOverlapWavToDebugDir = true,
                    deepgramApiKey = null
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

    /** 转写测试：仅在调用方显式提供 Deepgram API Key 时联网。 */
    private fun wearableDebugStartTranscription() {
        val svc = ensureWearableDebugService()
        (svc as? TranscriptionProvider)?.let { tp ->
            tp.setListener { text, startMs, endMs ->
                val msg = "onTranscriptionReady: \"$text\" [${startMs}ms - ${endMs}ms]"
                appendWearableDebugLog(msg)
                Log.d(NunaWearableServiceImpl.DEBUG_TAG, "[Transcription] $msg")
            }
            tp.startTranscription()
            appendWearableDebugLog("Start 转写已请求；未显式配置 Deepgram API Key 时不会联网")
            Log.d(NunaWearableServiceImpl.DEBUG_TAG, "[UI] Start transcription requested")
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
