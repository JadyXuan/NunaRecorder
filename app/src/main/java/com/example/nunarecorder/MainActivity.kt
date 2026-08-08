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
import com.example.nunarecorder.ble.RecordingControlEvent
import com.example.nunarecorder.ble.RecordingControlAction
import com.example.nunarecorder.ble.BatteryTelemetry
import com.example.nunarecorder.ble.DevicePowerState
import com.example.nunarecorder.ble.NunaProtocolInspector
import com.example.nunarecorder.ble.AudioCaptureBoundary
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

        private const val PREFERRED_ATT_MTU = 517
        private const val PRODUCT_AUDIO_MIN_ATT_MTU = 504
        private const val MTU_NEGOTIATION_TIMEOUT_MS = 2_000L
        private const val FIRST_AUDIO_TIMEOUT_MS = 8_000L
        private const val STOP_OVERALL_TIMEOUT_MS = 2_500L
        private const val STOP_BOUNDARY_TIMEOUT_MS = 1_600L
        private const val MAX_EARLY_AUDIO_PACKETS = 32
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
    private var negotiatedMtu = 23
    private var serviceDiscoveryStarted = false
    private var mtuNegotiationTimeout: Runnable? = null

    // Recording startup is intentionally two-phase: A003 transport readiness, then audio.
    private var firstAudioTimeout: Runnable? = null
    private var recordingStartCommandAttempted = false
    private var recordingControlStatus = "idle"
    private val earlyAudioPackets = ArrayDeque<ByteArray>()
    private var deviceReportedRecording: Boolean? = null
    private var recordingStopTimeout: Runnable? = null
    private var recordingStopFinalizeStarted = false
    private var pendingAudioDisableForStop = false
    private var recordingStopDetail = "录制已停止，设备保持连接"
    private var pendingStartAfterStop = false
    private var recordingStartRecoveryAttempted = false
    private val audioCaptureBoundary = AudioCaptureBoundary()
    private var stopMayFinalizeAtBoundary = false

    // 可选电量能力：XIAO 的 A004 优先，标准 BAS 作为原 Nuna 回退。
    private val batteryReadCandidates = ArrayDeque<BluetoothGattCharacteristic>()
    private var activeBatteryReadUuid: UUID? = null
    private var batteryNotificationUuid: UUID? = null
    private var batterySetupCompleted = false
    private var batteryReadTimeout: Runnable? = null

    // Protocol-test telemetry. Optional A001 subscription is only attempted after
    // the normal handshake-trigger read completes, so it cannot delay the proven flow.
    private var statusNotificationSetupAttempted = false
    private var statusNotificationUuid: UUID? = null
    private var protocolAudioPacketCount = 0L

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
            },
            onRecordingControlEvent = ::handleRecordingControlEvent
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
                                onDisconnectClick = { disconnectFromUi() },
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

    private fun handleRecordingControlEvent(event: RecordingControlEvent) {
        when (event) {
            is RecordingControlEvent.Accepted -> {
                recordingControlStatus = "${event.action.name.lowercase()}_accepted"
                diagnosticLogger.log(
                    "recording_control_accepted",
                    mapOf(
                        "command_id" to event.commandId,
                        "action" to event.action.name.lowercase(),
                        "mtu" to negotiatedMtu
                    )
                )
                if (event.action == RecordingControlAction.START) {
                    appendDebug("设备已接受开始录音命令 (cmdId=${event.commandId})")
                    if (viewModel.connectionState.value.phase == RecorderConnectionPhase.STARTING_RECORDING) {
                        transitionConnection(RecorderConnectionEvent.RecordingTransportReady)
                    }
                } else {
                    appendDebug("设备已接受停止录音命令 (cmdId=${event.commandId})")
                    if (deviceReportedRecording == false) {
                        beginStopTransportFinalize("设备已确认停止录音，GATT 保持连接")
                    } else {
                        awaitStopBoundaryOrTimeout("停止命令已确认，GATT 保持连接")
                    }
                }
            }
            is RecordingControlEvent.Rejected -> {
                recordingControlStatus = "${event.action.name.lowercase()}_rejected"
                diagnosticLogger.log(
                    "recording_control_rejected",
                    mapOf(
                        "command_id" to event.commandId,
                        "action" to event.action.name.lowercase(),
                        "status" to event.status,
                        "error_code" to event.errorCode,
                        "received_audio" to hasReceivedAudioPacket
                    )
                )
                val reason = "设备拒绝${if (event.action == RecordingControlAction.START) "开始" else "停止"}" +
                    "录音命令 (status=${event.status}, error=${event.errorCode ?: "未知"})"
                if (event.action == RecordingControlAction.STOP) {
                    appendDebug("$reason；按兼容模式等待完整音频组后关闭通知")
                    awaitStopBoundaryOrTimeout("停止反馈被拒绝，已关闭音频通道并保持连接")
                } else if (hasReceivedAudioPacket) {
                    // Legacy/XIAO behavior: CCCD subscription itself may already have started audio.
                    appendDebug("$reason；已有音频流，按兼容模式继续")
                } else {
                    recoverFailedRecordingStart(reason)
                }
            }
            is RecordingControlEvent.TimedOut -> {
                recordingControlStatus = "${event.action.name.lowercase()}_timeout"
                diagnosticLogger.log(
                    "recording_control_timeout",
                    mapOf(
                        "command_id" to event.commandId,
                        "action" to event.action.name.lowercase(),
                        "mtu" to negotiatedMtu,
                        "received_audio" to hasReceivedAudioPacket
                    )
                )
                if (event.action == RecordingControlAction.STOP) {
                    appendDebug("停止录音反馈超时；按兼容模式等待完整音频组后关闭通知")
                    awaitStopBoundaryOrTimeout("停止反馈超时，已关闭音频通道并保持连接")
                } else {
                    appendDebug("开始录音反馈超时；继续等待兼容设备的首个音频包")
                }
            }
        }
    }

    private fun scheduleFirstAudioTimeout() {
        firstAudioTimeout?.let(diagnosticHandler::removeCallbacks)
        firstAudioTimeout = Runnable {
            firstAudioTimeout = null
            if (viewModel.connectionState.value.phase == RecorderConnectionPhase.STARTING_RECORDING &&
                !hasReceivedAudioPacket
            ) {
                recoverFailedRecordingStart(
                    "${FIRST_AUDIO_TIMEOUT_MS / 1000} 秒内未收到音频" +
                        "（MTU=$negotiatedMtu，控制=$recordingControlStatus）"
                )
            }
        }.also { diagnosticHandler.postDelayed(it, FIRST_AUDIO_TIMEOUT_MS) }
    }

    private fun cancelFirstAudioTimeout() {
        firstAudioTimeout?.let(diagnosticHandler::removeCallbacks)
        firstAudioTimeout = null
    }

    @SuppressLint("MissingPermission")
    private fun disableAudioNotificationsBestEffort() {
        val activeGatt = gatt ?: return
        val characteristic = activeGatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID) ?: return
        try {
            activeGatt.setCharacteristicNotification(characteristic, false)
            characteristic.getDescriptor(CCCD_UUID)?.let { cccd ->
                cccd.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                activeGatt.writeDescriptor(cccd)
            }
        } catch (_: Exception) {
        }
    }

    private fun failRecordingStartup(reason: String) {
        if (viewModel.connectionState.value.phase != RecorderConnectionPhase.STARTING_RECORDING) return
        cancelFirstAudioTimeout()
        handshakeClient.cancelPendingRecordingControl()
        earlyAudioPackets.clear()
        stopRecordingDiagnostics("startup_failed")
        recording = false
        if (sessionRecorder.isRecording) sessionRecorder.stop()
        viewModel.setActiveRecordingPath(null)
        com.example.nunarecorder.service.ContextDataService.stop(this)
        disableAudioNotificationsBestEffort()
        transitionConnection(RecorderConnectionEvent.RecordingStartFailed(reason))
        appendLog("录制启动失败：$reason")
    }

    private fun recoverFailedRecordingStart(reason: String) {
        if (viewModel.connectionState.value.phase != RecorderConnectionPhase.STARTING_RECORDING) return
        val retry = !recordingStartRecoveryAttempted
        recordingStartRecoveryAttempted = true
        pendingStartAfterStop = retry
        transitionConnection(RecorderConnectionEvent.RecordingRecoveryStarted(reason))
        appendLog(
            if (retry) "录制启动异常：$reason；正在停止设备并自动重试一次"
            else "录制启动异常：$reason；正在停止设备并恢复就绪状态"
        )
        beginRecordingStop(
            detail = if (retry) "设备录音状态已复位，准备自动重试" else "设备录音状态已复位，可以重试"
        )
    }

    private fun beginRecordingStop(detail: String) {
        recordingStopDetail = detail
        recordingStopFinalizeStarted = false
        pendingAudioDisableForStop = false
        stopMayFinalizeAtBoundary = false
        recordingStopTimeout?.let(diagnosticHandler::removeCallbacks)
        cancelFirstAudioTimeout()
        handshakeClient.cancelPendingRecordingControl()
        earlyAudioPackets.clear()

        val activeGatt = gatt
        if (activeGatt == null) {
            closeLocalRecordingForStop()
            pendingStartAfterStop = false
            transitionConnection(RecorderConnectionEvent.ConnectionFailed("停止录音时 BLE 已断开"))
            return
        }

        val queued = handshakeClient.requestRecordingStop(activeGatt)
        diagnosticLogger.log(
            "recording_stop_requested",
            mapOf(
                "queued" to queued,
                "device_reported_recording" to deviceReportedRecording,
                "retry_after_stop" to pendingStartAfterStop
            )
        )
        if (!queued) {
            appendDebug("停止录音命令未入队；按兼容模式等待完整音频组后关闭 A003")
            awaitStopBoundaryOrTimeout("停止命令未入队，已关闭音频通道并保持连接")
            return
        }

        recordingStopTimeout = Runnable {
            recordingStopTimeout = null
            awaitStopBoundaryOrTimeout("停止确认超时，已关闭音频通道并保持连接")
        }.also { diagnosticHandler.postDelayed(it, STOP_OVERALL_TIMEOUT_MS) }
    }

    private fun awaitStopBoundaryOrTimeout(detail: String) {
        if (recordingStopFinalizeStarted ||
            viewModel.connectionState.value.phase != RecorderConnectionPhase.STOPPING_RECORDING
        ) return
        recordingStopDetail = detail
        recordingStopTimeout?.let(diagnosticHandler::removeCallbacks)
        recordingStopTimeout = null

        // A stale device-side recording can be stopped before a local session is opened.
        // There is no file boundary to preserve in that recovery path.
        if (!sessionRecorder.isRecording) {
            beginStopTransportFinalize(detail)
            return
        }

        stopMayFinalizeAtBoundary = true
        diagnosticLogger.log(
            "recording_stop_waiting_for_audio_boundary",
            mapOf(
                "already_at_group_end" to audioCaptureBoundary.lastWrittenPacketEndedGroup,
                "aligned" to audioCaptureBoundary.aligned,
                "skipped_leading_packets" to audioCaptureBoundary.skippedLeadingPackets
            )
        )
        if (audioCaptureBoundary.lastWrittenPacketEndedGroup) {
            beginStopTransportFinalize("$detail（已在完整音频组边界封口）")
            return
        }

        recordingStopTimeout = Runnable {
            recordingStopTimeout = null
            beginStopTransportFinalize("$detail（等待完整音频组超时）")
        }.also { diagnosticHandler.postDelayed(it, STOP_BOUNDARY_TIMEOUT_MS) }
    }

    @SuppressLint("MissingPermission")
    private fun beginStopTransportFinalize(detail: String) {
        if (recordingStopFinalizeStarted ||
            viewModel.connectionState.value.phase != RecorderConnectionPhase.STOPPING_RECORDING
        ) return
        recordingStopFinalizeStarted = true
        stopMayFinalizeAtBoundary = false
        recordingStopDetail = detail
        recordingStopTimeout?.let(diagnosticHandler::removeCallbacks)
        recordingStopTimeout = null
        handshakeClient.cancelPendingRecordingControl()
        closeLocalRecordingForStop()

        val activeGatt = gatt
        val characteristic = activeGatt?.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
        if (activeGatt == null || characteristic == null) {
            finalizeRecordingStop()
            return
        }

        activeGatt.setCharacteristicNotification(characteristic, false)
        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            finalizeRecordingStop()
            return
        }
        cccd.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        pendingAudioDisableForStop = true
        if (!activeGatt.writeDescriptor(cccd)) {
            pendingAudioDisableForStop = false
            finalizeRecordingStop()
        }
    }

    private fun closeLocalRecordingForStop() {
        stopRecordingDiagnostics("recording_stop_completed")
        recording = false
        if (sessionRecorder.isRecording) sessionRecorder.stop()
        viewModel.setActiveRecordingPath(null)
        com.example.nunarecorder.service.ContextDataService.stop(this)
    }

    private fun finalizeRecordingStop() {
        if (viewModel.connectionState.value.phase != RecorderConnectionPhase.STOPPING_RECORDING) return
        pendingAudioDisableForStop = false
        recordingStopFinalizeStarted = false
        stopMayFinalizeAtBoundary = false
        recordingStopTimeout?.let(diagnosticHandler::removeCallbacks)
        recordingStopTimeout = null
        val retry = pendingStartAfterStop
        pendingStartAfterStop = false
        transitionConnection(RecorderConnectionEvent.RecordingStopped(recordingStopDetail))
        appendLog(recordingStopDetail)
        diagnosticLogger.log(
            "recording_stop_completed",
            mapOf(
                "detail" to recordingStopDetail,
                "gatt_kept_connected" to (gatt != null),
                "automatic_retry" to retry
            )
        )
        if (retry && gatt != null) {
            diagnosticHandler.postDelayed({ startRecordingOnly(isAutomaticRetry = true) }, 350L)
        }
    }

    private fun resetStreamingStats() {
        totalPacketCount = 0
        totalBytesCount = 0
        lastAudioPacketElapsedMs = 0L
        lastAudioFrameId = null
        hasReceivedAudioPacket = false
        lastDiagnosticStatsMs = 0L
        lastDiagnosticRssiRequestMs = 0L
        recordingStartCommandAttempted = false
        recordingControlStatus = "pending"
        protocolAudioPacketCount = 0L
        audioCaptureBoundary.reset()
        stopMayFinalizeAtBoundary = false
        earlyAudioPackets.clear()
        cancelFirstAudioTimeout()
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
        "negotiated_mtu" to negotiatedMtu,
        "recording_control_status" to recordingControlStatus,
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
    private fun startRecordingOnly(isAutomaticRetry: Boolean = false) {
        if (!transitionConnectionNow(RecorderConnectionEvent.RecordingStartRequested)) {
            appendLog("设备尚未完成握手，暂时不能开始录制")
            return
        }
        if (gatt == null) {
            transitionConnectionNow(RecorderConnectionEvent.ConnectionFailed("BLE 连接已丢失"))
            appendLog("BLE 连接已丢失，请重新连接")
            return
        }
        if (!isAutomaticRetry) recordingStartRecoveryAttempted = false
        resetStreamingStats()
        appendLog("准备开始录制…")

        if (!isAutomaticRetry && deviceReportedRecording == null) {
            appendDebug("正在等待 A001 初始录音状态，避免接入残留音频流…")
            diagnosticHandler.postDelayed({
                if (viewModel.connectionState.value.phase == RecorderConnectionPhase.STARTING_RECORDING) {
                    continueRecordingStartAfterStatusCheck()
                }
            }, 700L)
            return
        }
        continueRecordingStartAfterStatusCheck()
    }

    private fun continueRecordingStartAfterStatusCheck() {
        if (deviceReportedRecording == true && !recordingStartRecoveryAttempted) {
            recordingStartRecoveryAttempted = true
            pendingStartAfterStop = true
            transitionConnection(
                RecorderConnectionEvent.RecordingRecoveryStarted("设备报告仍处于录音状态")
            )
            appendLog("设备仍处于录音状态，先发送停止命令清理旧会话")
            beginRecordingStop("旧录音状态已清理，准备开始新录音")
            return
        }

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
        if (negotiatedMtu < PRODUCT_AUDIO_MIN_ATT_MTU) {
            appendDebug(
                "当前 MTU=$negotiatedMtu，低于产品版 501 B 音频包建议值 " +
                    "$PRODUCT_AUDIO_MIN_ATT_MTU；仍将尝试兼容启动"
            )
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
        if (!transitionConnectionNow(RecorderConnectionEvent.StopRequested)) return
        appendLog("停止录制（共 ${totalPacketCount} 包 · ${formatBytes(totalBytesCount)}）")
        pendingStartAfterStop = false
        beginRecordingStop("录制已停止，设备保持连接")
    }

    private fun disconnectFromUi() {
        if (!transitionConnectionNow(RecorderConnectionEvent.DisconnectRequested)) return
        appendLog("正在断开设备…")
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


    @SuppressLint("MissingPermission")
    private fun beginGattInitialization(gatt: BluetoothGatt) {
        serviceDiscoveryStarted = false
        negotiatedMtu = 23
        mtuNegotiationTimeout?.let(diagnosticHandler::removeCallbacks)
        mtuNegotiationTimeout = null

        val priorityQueued = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        val mtuQueued = gatt.requestMtu(PREFERRED_ATT_MTU)
        diagnosticLogger.log(
            "gatt_link_setup_requested",
            mapOf(
                "preferred_mtu" to PREFERRED_ATT_MTU,
                "mtu_queued" to mtuQueued,
                "priority_queued" to priorityQueued
            )
        )
        appendDebug("正在协商 BLE MTU=$PREFERRED_ATT_MTU…")

        if (!mtuQueued) {
            appendDebug("MTU 请求未入队，使用系统默认值继续")
            discoverServicesOnce(gatt, "mtu_request_rejected")
            return
        }

        mtuNegotiationTimeout = Runnable {
            mtuNegotiationTimeout = null
            if (gatt === this.gatt && !serviceDiscoveryStarted) {
                appendDebug("MTU 协商回调超时，继续发现服务")
                diagnosticLogger.log("gatt_mtu_timeout", mapOf("assumed_mtu" to negotiatedMtu))
                discoverServicesOnce(gatt, "mtu_timeout")
            }
        }.also { diagnosticHandler.postDelayed(it, MTU_NEGOTIATION_TIMEOUT_MS) }
    }

    @SuppressLint("MissingPermission")
    private fun discoverServicesOnce(gatt: BluetoothGatt, reason: String) {
        if (gatt !== this.gatt || serviceDiscoveryStarted) return
        serviceDiscoveryStarted = true
        mtuNegotiationTimeout?.let(diagnosticHandler::removeCallbacks)
        mtuNegotiationTimeout = null

        val ok = gatt.discoverServices()
        diagnosticLogger.log(
            "gatt_service_discovery_requested",
            mapOf("accepted" to ok, "reason" to reason, "mtu" to negotiatedMtu)
        )
        if (!ok) {
            appendLog("服务发现启动失败")
            transitionConnection(RecorderConnectionEvent.ConnectionFailed("无法启动服务发现"))
        }
    }

    private fun resetGattInitialization() {
        mtuNegotiationTimeout?.let(diagnosticHandler::removeCallbacks)
        mtuNegotiationTimeout = null
        serviceDiscoveryStarted = false
        negotiatedMtu = 23
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
                resetGattInitialization()
                cancelFirstAudioTimeout()
                handshakeClient.cancelPendingRecordingControl()
                earlyAudioPackets.clear()
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
                beginGattInitialization(gatt)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                appendLog("已断开连接")
                stopRecordingDiagnostics("gatt_disconnected")
                recording = false
                isTransferNotificationEnabled = false
                resetBatteryTelemetry(null)
                resetGattInitialization()
                cancelFirstAudioTimeout()
                handshakeClient.cancelPendingRecordingControl()
                earlyAudioPackets.clear()

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
            logGattCapabilities(gatt)
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

            if (characteristic.uuid.toString()
                    .equals(ProtoConfig.Service.STATUS_CHAR_UUID, ignoreCase = true)
            ) {
                logProtocolPacket("a001_notify", value, includeStatus = true)
                handleStatusNotification(value)
            } else if (characteristic.uuid.toString()
                    .equals(ProtoConfig.Service.TRANSFER_CHAR_UUID, ignoreCase = true)
            ) {
                logProtocolPacket("a002_notify", value)
            }

            // 先让握手模块处理（只关心 A002）
            handshakeClient.onNotification(gatt, characteristic)

            BatteryTelemetry.parse(characteristic.uuid, value)?.let { state ->
                setDevicePowerState(state)
                logDevicePower("device_power_notification", state)
            }

            // 录音逻辑：只处理 A003（不打印数据）
            if (characteristic.uuid == CHAR_UUID) {
                handleAudioNotification(value)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (characteristic.uuid.toString()
                    .equals(ProtoConfig.Service.STATUS_CHAR_UUID, ignoreCase = true)
            ) {
                diagnosticLogger.log(
                    "protocol_characteristic_read",
                    mapOf("source" to "a001", "status" to status, "bytes" to value.size)
                )
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    logProtocolPacket("a001_read", value, includeStatus = true)
                }
                enableStatusNotificationsBestEffort(gatt, characteristic)
            }
            handleBatteryCharacteristicRead(gatt, characteristic, value, status)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val value = characteristic.value ?: byteArrayOf()
            if (characteristic.uuid.toString()
                    .equals(ProtoConfig.Service.STATUS_CHAR_UUID, ignoreCase = true)
            ) {
                diagnosticLogger.log(
                    "protocol_characteristic_read",
                    mapOf("source" to "a001_legacy_callback", "status" to status, "bytes" to value.size)
                )
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    logProtocolPacket("a001_read", value, includeStatus = true)
                }
                enableStatusNotificationsBestEffort(gatt, characteristic)
            }
            handleBatteryCharacteristicRead(
                gatt,
                characteristic,
                value,
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

            if (descriptor.uuid == CCCD_UUID &&
                descriptor.characteristic.uuid == statusNotificationUuid
            ) {
                diagnosticLogger.log(
                    "protocol_status_subscription",
                    mapOf("status" to status, "enabled" to (status == BluetoothGatt.GATT_SUCCESS))
                )
                appendDebug(
                    if (status == BluetoothGatt.GATT_SUCCESS) "协议探测：A001 状态通知已启用"
                    else "协议探测：A001 状态通知启用失败 (status=$status)"
                )
                statusNotificationUuid = null
            }

            if (descriptor.uuid == CCCD_UUID && descriptor.characteristic.uuid == CHAR_UUID) {
                if (pendingAudioDisableForStop &&
                    viewModel.connectionState.value.phase == RecorderConnectionPhase.STOPPING_RECORDING
                ) {
                    pendingAudioDisableForStop = false
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        recordingStopDetail += "（A003 通知关闭反馈=$status）"
                    }
                    finalizeRecordingStop()
                } else if (status == BluetoothGatt.GATT_SUCCESS &&
                    viewModel.connectionState.value.phase == RecorderConnectionPhase.STARTING_RECORDING
                ) {
                    if (openFileForRecording()) {
                        appendLog("音频通知已启用，正在请求设备开始录音…")
                        scheduleFirstAudioTimeout()
                        sendRecordingStartCommandIfNeeded(gatt)
                        flushEarlyAudioPackets()
                    } else {
                        gatt.setCharacteristicNotification(descriptor.characteristic, false)
                        failRecordingStartup("无法创建录音会话")
                    }
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    gatt.setCharacteristicNotification(descriptor.characteristic, false)
                    failRecordingStartup("启用音频通知失败 (status=$status)")
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            super.onReadRemoteRssi(gatt, rssi, status)
            diagnosticLogger.log("gatt_rssi", mapOf("status" to status, "rssi_dbm" to rssi))
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            if (gatt !== this@MainActivity.gatt) return
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            diagnosticLogger.log("gatt_mtu", mapOf("status" to status, "mtu" to mtu))
            appendDebug(
                if (status == BluetoothGatt.GATT_SUCCESS) "BLE MTU 已协商为 $mtu"
                else "BLE MTU 协商失败 (status=$status)，使用默认值继续"
            )
            discoverServicesOnce(gatt, "mtu_callback_$status")
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
        statusNotificationSetupAttempted = false
        statusNotificationUuid = null
        protocolAudioPacketCount = 0L
        deviceReportedRecording = null
        recordingStopTimeout?.let(diagnosticHandler::removeCallbacks)
        recordingStopTimeout = null
        recordingStopFinalizeStarted = false
        pendingAudioDisableForStop = false
        stopMayFinalizeAtBoundary = false
        audioCaptureBoundary.reset()
        pendingStartAfterStop = false
        recordingStartRecoveryAttempted = false
        setDevicePowerState(state)
    }

    private fun logGattCapabilities(gatt: BluetoothGatt) {
        gatt.services.forEach { service ->
            service.characteristics.forEach { characteristic ->
                val properties = characteristicPropertyNames(characteristic.properties)
                diagnosticLogger.log(
                    "gatt_characteristic_capability",
                    mapOf(
                        "service_uuid" to service.uuid.toString(),
                        "characteristic_uuid" to characteristic.uuid.toString(),
                        "properties" to properties,
                        "property_bits" to "0x${characteristic.properties.toString(16)}",
                        "descriptors" to characteristic.descriptors.joinToString(",") { it.uuid.toString() }
                    )
                )
                Log.d(TAG, "PROTO GATT ${service.uuid}/${characteristic.uuid} $properties")
            }
        }

        val a004 = gatt.getService(SERVICE_UUID)
            ?.getCharacteristic(BatteryTelemetry.NUNA_POWER_UUID)
        val a004Role = when {
            a004 == null -> "absent"
            a004.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 ->
                "xiao_power_candidate"
            a004.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 ->
                "product_offline_audio_candidate"
            else -> "unknown"
        }
        diagnosticLogger.log(
            "protocol_a004_classification",
            mapOf(
                "role" to a004Role,
                "properties" to a004?.let { characteristicPropertyNames(it.properties) }
            )
        )
        Log.d(TAG, "PROTO A004 role=$a004Role properties=${a004?.let { characteristicPropertyNames(it.properties) }}")
    }

    private fun characteristicPropertyNames(properties: Int): String {
        val names = mutableListOf<String>()
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) names += "READ"
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) names += "WRITE"
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) names += "WRITE_NO_RESPONSE"
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) names += "NOTIFY"
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) names += "INDICATE"
        if (properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) names += "BROADCAST"
        if (properties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) names += "SIGNED_WRITE"
        return names.joinToString("|").ifEmpty { "NONE" }
    }

    private fun logProtocolPacket(source: String, raw: ByteArray, includeStatus: Boolean = false) {
        val envelope = NunaProtocolInspector.parseEnvelope(raw)
        if (envelope == null) {
            diagnosticLogger.log(
                "protocol_envelope_invalid",
                mapOf("source" to source, "raw_bytes" to raw.size, "prefix_hex" to raw.toBoundedHex())
            )
            Log.d(TAG, "PROTO $source invalid bytes=${raw.size} prefix=${raw.toBoundedHex()}")
            return
        }
        Log.d(
            TAG,
            "PROTO $source type=0x${envelope.type.toString(16).padStart(2, '0')} " +
                "data=${envelope.declaredDataLength} version=${envelope.version} " +
                "checksum=${envelope.checksumClassification} trailing=${envelope.trailingBytes}"
        )
        diagnosticLogger.log(
            "protocol_envelope",
            NunaProtocolInspector.envelopeFields(envelope) +
                mapOf("source" to source, "raw_bytes" to raw.size)
        )
        if (includeStatus) {
            val status = NunaProtocolInspector.parseStatus(envelope)
            Log.d(TAG, "PROTO $source status=${status.name} fields=${status.fields}")
            diagnosticLogger.log(
                "protocol_status_message",
                status.fields + mapOf(
                    "source" to source,
                    "status_type" to status.name,
                    "message_type" to "0x${envelope.type.toString(16).padStart(2, '0')}"
                )
            )
        }
    }

    private fun handleStatusNotification(raw: ByteArray) {
        val envelope = NunaProtocolInspector.parseEnvelope(raw) ?: return
        if (envelope.type != 0x11 || envelope.data.isEmpty()) return
        val reportedRecording = (envelope.data[0].toInt() and 0xFF) == 1
        deviceReportedRecording = reportedRecording
        diagnosticLogger.log(
            "device_recording_status",
            mapOf(
                "recording" to reportedRecording,
                "app_phase" to viewModel.connectionState.value.phase.name
            )
        )
        if (!reportedRecording &&
            viewModel.connectionState.value.phase == RecorderConnectionPhase.STOPPING_RECORDING
        ) {
            beginStopTransportFinalize("设备已确认停止录音，GATT 保持连接")
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableStatusNotificationsBestEffort(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        if (statusNotificationSetupAttempted || gatt !== this.gatt) return
        statusNotificationSetupAttempted = true

        val supportsNotify = characteristic.properties and
            BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val supportsIndicate = characteristic.properties and
            BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if ((!supportsNotify && !supportsIndicate) || cccd == null) {
            diagnosticLogger.log(
                "protocol_status_subscription",
                mapOf("enabled" to false, "reason" to "unsupported_or_missing_cccd")
            )
            return
        }
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            diagnosticLogger.log(
                "protocol_status_subscription",
                mapOf("enabled" to false, "reason" to "local_registration_rejected")
            )
            return
        }

        cccd.value = if (supportsNotify) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        statusNotificationUuid = characteristic.uuid
        if (!gatt.writeDescriptor(cccd)) {
            statusNotificationUuid = null
            gatt.setCharacteristicNotification(characteristic, false)
            diagnosticLogger.log(
                "protocol_status_subscription",
                mapOf("enabled" to false, "reason" to "descriptor_write_rejected")
            )
        }
    }

    private fun ByteArray.toBoundedHex(limit: Int = 48): String {
        val shown = take(limit).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        return if (size > limit) "$shown …(+${size - limit}B)" else shown
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
            ?.takeIf {
                it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
            }
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
            failRecordingStartup("缺少蓝牙连接权限")
            requestBlePermissions()
            return
        }

        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            appendLog("无法在本机启用音频通知")
            failRecordingStartup("本机通知注册失败")
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
                failRecordingStartup("设备拒绝通知配置")
            }
        } else {
            appendLog("Recording CCCD not found, cannot enable notifications.")
            gatt.setCharacteristicNotification(characteristic, false)
            failRecordingStartup("音频通知描述符不存在")
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
            cancelFirstAudioTimeout()
            handshakeClient.cancelPendingRecordingControl()
            earlyAudioPackets.clear()
            resetGattInitialization()
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

    private fun sendRecordingStartCommandIfNeeded(gatt: BluetoothGatt) {
        if (recordingStartCommandAttempted) return
        recordingStartCommandAttempted = true
        val queued = handshakeClient.requestRecordingStart(gatt)
        recordingControlStatus = if (queued) "pending" else "not_queued"
        diagnosticLogger.log(
            "recording_control_requested",
            mapOf("queued" to queued, "mtu" to negotiatedMtu)
        )
        if (!queued) {
            // Do not fail yet: old XIAO/legacy Nuna starts streaming when A003 CCCD is enabled.
            appendDebug("录音控制命令未入队；继续等待兼容设备自动推流")
        }
    }

    private fun flushEarlyAudioPackets() {
        if (earlyAudioPackets.isEmpty()) return
        appendDebug("处理订阅确认前缓存的 ${earlyAudioPackets.size} 个音频包")
        while (earlyAudioPackets.isNotEmpty()) {
            processAudioNotification(earlyAudioPackets.removeFirst())
        }
    }

    private fun handleAudioNotification(value: ByteArray) {
        val phase = viewModel.connectionState.value.phase
        if (phase != RecorderConnectionPhase.STARTING_RECORDING &&
            phase != RecorderConnectionPhase.RECORDING &&
            phase != RecorderConnectionPhase.AUDIO_STALLED &&
            !(phase == RecorderConnectionPhase.STOPPING_RECORDING &&
                hasReceivedAudioPacket && sessionRecorder.isRecording)
        ) {
            diagnosticLogger.log(
                "audio_notification_ignored",
                mapOf("phase" to phase.name, "bytes" to value.size)
            )
            return
        }

        if (!sessionRecorder.isRecording) {
            if (earlyAudioPackets.size >= MAX_EARLY_AUDIO_PACKETS) {
                earlyAudioPackets.removeFirst()
                diagnosticLogger.log("early_audio_buffer_overflow")
            }
            earlyAudioPackets.addLast(value.copyOf())
            return
        }
        processAudioNotification(value)
    }

    private fun processAudioNotification(value: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        val firstPacket = !hasReceivedAudioPacket
        val parsedAudio = NunaProtocolInspector.parseAudio(value)
        val boundaryDecision = audioCaptureBoundary.onPacket(parsedAudio)
        totalPacketCount++
        protocolAudioPacketCount++
        totalBytesCount += value.size
        lastAudioPacketElapsedMs = now
        lastAudioFrameId = extractAudioFrameId(value) ?: lastAudioFrameId
        hasReceivedAudioPacket = true

        if (protocolAudioPacketCount <= 8L || protocolAudioPacketCount % 100L == 0L) {
            val envelope = NunaProtocolInspector.parseEnvelope(value)
            val audio = parsedAudio
            if (envelope != null && audio != null) {
                Log.d(
                    TAG,
                    "PROTO A003 #$protocolAudioPacketCount frame=${audio.frameId} " +
                        "frameSize=${audio.frameSize} chunk=${audio.chunkId}/${audio.totalChunks} " +
                        "payload=${audio.payloadBytes} opusFrames=${audio.opusFramesInPayload} " +
                        "ts=${audio.timestampMs} checksum=${envelope.checksumClassification}"
                )
                diagnosticLogger.log(
                    "protocol_audio_packet",
                    NunaProtocolInspector.envelopeFields(envelope) +
                        NunaProtocolInspector.audioFields(audio) +
                        mapOf("packet_index" to protocolAudioPacketCount, "raw_bytes" to value.size)
                )
            } else {
                diagnosticLogger.log(
                    "protocol_audio_packet_invalid",
                    mapOf(
                        "packet_index" to protocolAudioPacketCount,
                        "raw_bytes" to value.size,
                        "prefix_hex" to value.toBoundedHex()
                    )
                )
            }
        }

        if (firstPacket) {
            cancelFirstAudioTimeout()
            recordingStartRecoveryAttempted = false
            pendingStartAfterStop = false
            deviceReportedRecording = true
            recording = true
            startRecordingDiagnostics()
            transitionConnection(RecorderConnectionEvent.RecordingStarted)
            appendLog("录制已开始（收到首个音频包 · ${value.size} B · MTU=$negotiatedMtu）")
            diagnosticLogger.log(
                "first_audio_notification",
                mapOf(
                    "bytes" to value.size,
                    "mtu" to negotiatedMtu,
                    "control_status" to recordingControlStatus
                )
            )
        } else {
            audioStallMonitor.onPacket(now)?.let(::handleAudioStallEvent)
        }

        if (boundaryDecision.writePacket) {
            writeToFile(value)
        } else {
            val audio = parsedAudio
            appendDebug(
                "跳过会话开头的不完整音频分块 " +
                    "frame=${audio?.frameId} chunk=${audio?.chunkId}/${audio?.totalChunks}"
            )
            diagnosticLogger.log(
                "audio_leading_chunk_skipped",
                mapOf(
                    "frame_id" to audio?.frameId,
                    "chunk_id" to audio?.chunkId,
                    "total_chunks" to audio?.totalChunks,
                    "skipped_count" to audioCaptureBoundary.skippedLeadingPackets
                )
            )
        }

        if (stopMayFinalizeAtBoundary && boundaryDecision.groupEndAfterWrite &&
            viewModel.connectionState.value.phase == RecorderConnectionPhase.STOPPING_RECORDING
        ) {
            beginStopTransportFinalize("$recordingStopDetail（已在完整音频组边界封口）")
        }
    }

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
        val integrityIssue = sessionRecorder.feed(data)
        updateLiveRecordingStatsUi()
        if (integrityIssue != null && recording) {
            appendLog(
                "检测到 BLE 音频缺口（$integrityIssue）；将继续录制，" +
                    "当前分段会标记为不完整且不会自动上传"
            )
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
        segmentPlayer.stop()
        cancelFirstAudioTimeout()
        recordingStopTimeout?.let(diagnosticHandler::removeCallbacks)
        recordingStopTimeout = null
        stopRecordingDiagnostics("activity_destroyed")
        recording = false
        if (sessionRecorder.isRecording) sessionRecorder.stop()
        viewModel.setActiveRecordingPath(null)
        com.example.nunarecorder.service.ContextDataService.stop(this)
        if (gatt != null) stopNotifyAndDisconnect()
        // DEBUG_WEARABLE_START
        wearableDebugService?.stopRecording()
        wearableDebugService = null
        // DEBUG_WEARABLE_END
        super.onDestroy()
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
