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
import androidx.compose.runtime.collectAsState
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
import com.example.nunarecorder.enroll.EnrollmentCodec
import com.example.nunarecorder.enroll.EnrollmentConfigClient
import com.example.nunarecorder.enroll.EnrollmentParseResult
import com.example.nunarecorder.enroll.EnrollmentStore
import com.example.nunarecorder.data.RecordingEntry
import com.example.nunarecorder.data.LogLevel
import com.example.nunarecorder.migration.MigrationCoordinator
import com.example.nunarecorder.recording.RecordingController
import com.example.nunarecorder.sync.ServerHandshakeCheck
import com.example.nunarecorder.sync.SessionSyncCoordinator
import com.example.nunarecorder.session.SessionPaths
import com.example.nunarecorder.util.AppVersionCheck
import com.example.nunarecorder.util.CollectionReadiness
import com.example.nunarecorder.util.DiagnosticsLog
import com.example.nunarecorder.vad.VadJobQueue
import com.example.nunarecorder.data.DeviceStorage
import com.example.nunarecorder.data.PairedDevice
import com.example.nunarecorder.data.ScannedDevice
import com.example.nunarecorder.ui.components.BottomNavBar
import com.example.nunarecorder.ui.screen.MainScreen
import com.example.nunarecorder.ui.MainViewModel
import com.example.nunarecorder.ui.LiveRecordingUiStats
import com.example.nunarecorder.ui.screen.RecordingsScreen
import com.example.nunarecorder.ui.screen.EnrollScreen
import com.example.nunarecorder.ui.screen.LoginGuideScreen
import com.example.nunarecorder.ui.screen.VoiceprintScreen
import com.example.nunarecorder.voiceprint.VoiceprintSession
import com.example.nunarecorder.voiceprint.VoiceprintUploader
import com.example.nunarecorder.ui.screen.SettingsScreen
import com.example.wearable.TranscriptionProvider
import com.example.wearable.WearableConnectionConfig
import com.example.wearable.impl.NunaWearableServiceImpl
import com.example.wearable.internal.WearableBleConfig
import com.example.nunarecorder.ui.theme.NunaRecorderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    companion object {
        private const val TAG = "NunaRecorder"
    }

    // 记录当前选中的设备 MAC 地址（来自列表点击，存于 ViewModel）

    // 当前连接设备名（用于文件命名）
    private var currentDeviceName: String = "unknown"

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false

    // DEBUG_WEARABLE_START — 与采集服务的 GATT 独立；若正在采集请先停止再测
    private var wearableDebugService: NunaWearableServiceImpl? = null
    // DEBUG_WEARABLE_END

    private fun appendLog(msg: String, level: LogLevel = LogLevel.INFO) {
        Log.d(TAG, msg)
        DiagnosticsLog.log("UI", msg)
        runOnUiThread { viewModel.appendLog(msg, level) }
    }

    private fun appendDebug(msg: String) = appendLog(msg, LogLevel.DEBUG)

    private val segmentPlayer = SegmentAudioPlayer { appendDebug(it) }

    /**
     * 上传用的 HTTP 客户端。
     *
     * 裸 `OkHttpClient()` 没有 `callTimeout`，单个请求可以无限挂着——2026-08-03 实测
     * 上传卡在 2/96、点取消没反应、过了很久才自己结束，就是这个。
     * `callTimeout` 是唯一能兜住"整个请求"的超时；read/write timeout 只约束单次 IO，
     * 一个慢而不断的连接可以永远续命。
     */
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }


    // 持久化的已配对设备存储
    private lateinit var deviceStorage: DeviceStorage

    // 用户设置存储
    private lateinit var userSettingsStorage: UserSettingsStorage

    // 服务器地址 / 参与者编号 / 令牌的唯一来源
    private lateinit var enrollmentStore: EnrollmentStore

    // 当前选中的“已配对设备”
    private var selectedPairedDevice: PairedDevice? = null


    /** 扫码入组：结果是原始入组码文本，交给同一条 applyEnrollmentCode 路径 */
    private val enrollScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(
                com.example.nunarecorder.enroll.EnrollScanActivity.EXTRA_CODE
            )?.let { applyEnrollmentCode(it) }
        }
    }

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

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        deviceStorage = DeviceStorage(this)
        userSettingsStorage = UserSettingsStorage(this)
        enrollmentStore = EnrollmentStore(this)

        // 初始化已配对设备列表
        refreshPairedDeviceList()

        // 加载用户设置
        val initialSettings = userSettingsStorage.load()
        viewModel.setUserSettings(initialSettings)
        viewModel.setEnrollment(enrollmentStore.current(), enrollmentStore.isRevoked())
        enrollmentStore.current()?.let { checkAppVersion(it) }

        requestBlePermissions()
        VadJobQueue.start(this)
        val resumed = VadJobQueue.resumeAllIncompleteSessions()
        if (resumed > 0) appendLog("自动续传 VAD: $resumed 个音频段待分析")
        DiagnosticsLog.appVersion = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
            "${info.versionName} ($code)"
        }.getOrDefault("unknown")
        MigrationCoordinator.onLog = { appendLog(it) }
        SessionSyncCoordinator.onLog = { appendLog(it) }
        SessionSyncCoordinator.onTokenRejected = {
            enrollmentStore.markRevoked()
            runOnUiThread {
                viewModel.setEnrollment(enrollmentStore.current(), true)
            }
        }

        setContent {
            val logText by viewModel.logText
            val pickedDeviceAddress by viewModel.selectedDeviceAddress
            val userSettings by viewModel.userSettings
            val settingsCheck by viewModel.settingsCheckResult
            val settingsChecking by viewModel.settingsCheckRunning
            val enrollment by viewModel.enrollment
            val enrollRevoked by viewModel.enrollmentRevoked
            val voiceprint by VoiceprintSession.state.collectAsState()
            val vpUploading by viewModel.voiceprintUploading
            val vpMessage by viewModel.voiceprintMessage
            // 采集状态的唯一来源是服务，不是 Activity 的字段
            val linkStatus by RecordingController.link.collectAsState()
            val recorderStats by RecordingController.stats.collectAsState()
            val liveRecordingStats = recorderStats?.let { LiveRecordingUiStats.from(it) }
            val activeRecordingPath = liveRecordingStats?.sessionPath
            // Activity 可以被回收而服务还在采集。重建后 ViewModel 里的选择是空的，
            // 界面会显示"请先选择设备"——而此刻它其实正在采那台设备。以服务的状态兜底。
            val selectedDeviceAddress = pickedDeviceAddress ?: linkStatus.deviceAddress

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
                                logText = logText,
                                deviceList = viewModel.deviceList,
                                pairedDevices = viewModel.pairedDevices,
                                selectedDeviceAddress = selectedDeviceAddress,
                                linkStatus = linkStatus,
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
                                onStartRecordingClick = { startRecording() },
                                onStopRecordingClick = { stopRecording() },
                                onExportLog = { exportDiagnosticsLog() },
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
                            onUploadAllPending = { uploadAllPending() },
                            onDeleteSynced = { onDone -> deleteSyncedSessions(onDone) },
                            onMigrateLegacy = { opus, options ->
                                MigrationCoordinator.start(this@MainActivity, opus, options)
                            },
                            activeRecordingPath = activeRecordingPath,
                            liveRecordingStats = liveRecordingStats,
                            modifier = Modifier.fillMaxSize()
                        )
                            2 -> EnrollScreen(
                                current = enrollment,
                                enrolledAtMs = enrollmentStore.enrolledAtMs(),
                                tokenRevoked = enrollRevoked,
                                checkResult = settingsCheck,
                                checkRunning = settingsChecking,
                                onScanClick = {
                                    enrollScanLauncher.launch(
                                        android.content.Intent(
                                            this@MainActivity,
                                            com.example.nunarecorder.enroll.EnrollScanActivity::class.java
                                        )
                                    )
                                },
                                onCodeEntered = { raw -> applyEnrollmentCode(raw) },
                                onRecheck = { enrollment?.let { runServerCheck(it) } },
                                onClearEnrollment = {
                                    enrollmentStore.clear()
                                    viewModel.setEnrollment(null, false)
                                    viewModel.settingsCheckResult.value = null
                                    appendLog("已清除入组配置（本地录音不受影响）")
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                            3 -> LoginGuideScreen(
                                enrollment = enrollment,
                                onCopy = { label, value ->
                                    val cm = getSystemService(Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
                                    appendLog("已复制$label")
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                            4 -> VoiceprintScreen(
                                state = voiceprint,
                                linkStreaming = linkStatus.isStreaming,
                                uploading = vpUploading,
                                uploadMessage = vpMessage,
                                onStart = { step -> VoiceprintSession.start(this@MainActivity, step) },
                                onStop = {
                                    val r = VoiceprintSession.stop()
                                    r?.let { appendLog("声纹：${it.advice}") }
                                },
                                onUpload = { uploadVoiceprints() },
                                onSkip = { selectedTab = 0 },
                                modifier = Modifier.fillMaxSize()
                            )
                            5 -> SettingsScreen(
                                userSettings = userSettings,
                                onLogLevelChange = { level ->
                                    viewModel.setUserSettings(userSettings.copy(logLevel = level))
                                },
                                onAutoVadChange = { enabled ->
                                    viewModel.setUserSettings(userSettings.copy(autoVadOnRecord = enabled))
                                },
                                onSave = {
                                    userSettingsStorage.save(userSettings)
                                    appendLog("设置已保存")
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
        appendLog("开始扫描附近设备…")
        startScan(targetNameFilter = null)
    }

    /**
     * 开始采集：把设备交给 [com.example.nunarecorder.service.RecordingService]。
     *
     * Activity 不再持有 GATT 或录制管线。原来「连接 + 握手」和「开始录制」是两个按钮、
     * 两段状态，中间任何一步失败都没人发现；现在只有一个入口，链路健康由状态面板显示。
     */
    private fun startRecording() {
        if (bluetoothAdapter?.isEnabled != true) {
            appendLog("请先开启蓝牙")
            return
        }
        val address = viewModel.selectedDeviceAddress.value
        if (address.isNullOrBlank()) {
            appendLog("请先在上方选择要连接的设备")
            return
        }
        val name = viewModel.deviceList.find { it.address == address }?.name
            ?: viewModel.pairedDevices.find { it.address == address }?.name
            ?: selectedPairedDevice?.name
            ?: address
        // 权限少开不会让 App 崩，只会让某个模态静默缺失，等数据回来才发现——
        // 那时参与者已经走了。所以在开始之前就摆出来。
        val readiness = CollectionReadiness.check(this)
        readiness.items.filter { it.level != CollectionReadiness.Level.OK }.forEach {
            appendLog("自检: ${it.title} —— ${it.consequence}")
        }
        DiagnosticsLog.log("Readiness", readiness.summary())
        if (!readiness.canRecord) {
            appendLog("无法开始采集，请先解决上面标注的问题")
            viewModel.readinessReport.value = readiness
            return
        }
        viewModel.readinessReport.value = readiness

        stopScan()
        deviceStorage.saveOrUpdateDevice(
            PairedDevice(
                name = name.takeIf { it != address },
                address = address,
                lastConnectedTime = System.currentTimeMillis()
            )
        )
        refreshPairedDeviceList()
        appendLog("开始采集：$name")
        RecordingController.start(this, name, address)
    }

    private fun stopRecording() {
        appendLog("停止采集")
        RecordingController.stop(this)
    }

    /**
     * 保存设置后立刻做一次服务器握手自检，把结果显示出来。
     *
     * 参与者在入组现场就该知道配置对不对——原来点保存没有任何反馈，
     * 采完一整天才发现传不上去已经太晚了。
     */
    /** 应用一个入组码：解析 → 存下 → 立刻自检一次。 */
    private fun applyEnrollmentCode(raw: String) {
        when (val r = EnrollmentCodec.parse(raw)) {
            is EnrollmentParseResult.Error -> appendLog("入组失败：${r.reason}")
            is EnrollmentParseResult.Ok -> {
                enrollmentStore.save(r.code)
                viewModel.setEnrollment(r.code, false)
                // 令牌本身绝不进日志，只留前 4 位指纹
                appendLog("已入组：${r.code.participantId} · 令牌 ${r.code.tokenFingerprint}…")
                lifecycleScope.launch {
                    // 二维码装不下标注网址和网关凭据，入组后用令牌向服务端补齐
                    val client = EnrollmentConfigClient(httpClient)
                    val outcome = withContext(Dispatchers.IO) { client.fetch(r.code) }
                    val merged = client.merge(r.code, outcome.config)
                    if (merged != r.code) {
                        enrollmentStore.save(merged)
                        viewModel.setEnrollment(merged, false)
                        appendLog("已从服务器补齐标注网址与登录指引")
                    }
                    // 拿不到就说出为什么。研究员此刻还站在旁边，是唯一能当场补救的时刻；
                    // 原来这里失败是完全静默的，参与者要到回家登录网站时才发现没有凭据。
                    outcome.problem?.let { appendLog("没能取到登录凭据：$it") }
                        runServerCheck(merged)
                }
            }
        }
    }

    /** 启动后顺带查一次有没有新版；只提示，不自动安装。 */
    private fun checkAppVersion(code: com.example.nunarecorder.enroll.EnrollmentCode) {
        val current = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
        }.getOrDefault(0L)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                AppVersionCheck.check(httpClient, code.serverUrl, current)
            } ?: return@launch
            if (r.hasUpdate) {
                appendLog("有新版本 ${r.latestName}（${r.latestCode}），当前 $current。请到 ${code.serverUrl}/apk/latest 下载更新")
            }
        }
    }

    private fun runServerCheck(code: com.example.nunarecorder.enroll.EnrollmentCode) {
        viewModel.settingsCheckRunning.value = true
        viewModel.settingsCheckResult.value = null
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { ServerHandshakeCheck.run(httpClient, code) }
            viewModel.settingsCheckRunning.value = false
            viewModel.settingsCheckResult.value = result
            result.lines.forEach { appendLog("自检: ${it.text}") }
        }
    }

    /**
     * 导出诊断日志。没有日志就定位不了佩戴者报告的闪退和断连，
     * 所以这是排查 P0 问题的前提，不是可有可无的辅助功能。
     */
    private fun exportDiagnosticsLog() {
        try {
            val file = DiagnosticsLog.exportTo(this)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, file.name)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "导出诊断日志"))
            appendLog("已导出 ${DiagnosticsLog.lineCount()} 行日志：${file.name}")
        } catch (e: Exception) {
            appendLog("导出日志失败: ${e.message}")
        }
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
                    appendLog("找到设备 $name")
                    stopScan()
                    viewModel.selectDevice(address)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            appendLog("扫描失败 (code=$errorCode)")
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
        val code = enrollmentStore.current()
        if (code == null) {
            appendLog("还没有入组配置，无法上传。请到「入组」页扫描二维码。")
            return
        }
        appendLog("开始同步: ${entry.displayName}")
        SessionSyncCoordinator.start(
            entry = entry,
            includeContext = withContext,
            includeVad = withVad,
            enrollment = code,
            httpClient = httpClient
        )
    }

    /** 一键上传所有未同步会话，按录制时间先后排队。 */
    private fun uploadAllPending() {
        val code = enrollmentStore.current()
        if (code == null) {
            appendLog("还没有入组配置，无法上传")
            return
        }
        val pending = SessionPaths.listSessionDirs().mapNotNull { dir ->
            val status = com.example.nunarecorder.sync.SessionSyncStatus.load(dir)
            if (status?.status == "synced") return@mapNotNull null
            val manifest = com.example.nunarecorder.session.SessionManifest
                .load(SessionPaths.manifestFile(dir)) ?: return@mapNotNull null
            if (manifest.recordingActive) return@mapNotNull null   // 正在录的不动
            RecordingEntry.Session(dir = dir, manifest = manifest)
        }
        if (pending.isEmpty()) {
            appendLog("没有需要上传的会话")
            return
        }
        SessionSyncCoordinator.startBatch(
            entries = pending,
            includeContext = true,
            includeVad = true,
            enrollment = code,
            httpClient = httpClient
        )
    }

    /** 删除已确认同步的会话；未确认的一律不动。 */
    /**
     * 清理已同步会话。**整个过程都在 IO 线程**。
     *
     * 2026-08-09 用户实测"点击清理已同步（35 条）还发生过一次闪退"——原来这里
     * 在主线程上先 walk 每个会话的全部文件求体积，再 deleteRecursively 删掉几千个
     * 文件，主线程一卡死就是 ANR。会话越多越必然，而明天会有 700–900 条。
     */
    private fun deleteSyncedSessions(onDone: () -> Unit) {
        lifecycleScope.launch {
            appendLog("正在统计可清理的会话…")
            val cleaner = com.example.nunarecorder.recording.SyncedSessionCleaner
            val candidates = withContext(Dispatchers.IO) { cleaner.listDeletable() }
            if (candidates.isEmpty()) {
                appendLog("没有已确认同步的会话可以清理")
                onDone()
                return@launch
            }
            appendLog("正在清理 ${candidates.size} 个已同步会话…")
            val r = withContext(Dispatchers.IO) { cleaner.deleteAll(candidates) }
            appendLog(
                "已清理 ${r.deleted} 个已同步会话，释放 " +
                    LiveRecordingUiStats.formatBytes(r.freedBytes) +
                    if (r.failed.isEmpty()) "" else "；跳过 ${r.failed.size} 个"
            )
            onDone()
        }
    }

    /** 两段声纹依次上传；任一失败就停下并保留本地文件。 */
    private fun uploadVoiceprints() {
        val code = enrollmentStore.current()
        if (code == null) {
            appendLog("还没有入组配置，无法上传声纹")
            return
        }
        viewModel.voiceprintUploading.value = true
        viewModel.voiceprintMessage.value = null
        lifecycleScope.launch {
            val uploader = VoiceprintUploader(httpClient)
            val steps = listOf(
                VoiceprintSession.Step.READ to VoiceprintUploader.Kind.READ,
                VoiceprintSession.Step.FREE to VoiceprintUploader.Kind.FREE
            )
            var message = "声纹已全部上传"
            for ((step, kind) in steps) {
                val file = VoiceprintSession.fileFor(this@MainActivity, step)
                val r = withContext(Dispatchers.IO) { uploader.upload(code, kind, file) }
                appendLog("声纹 ${kind.wire}：${r.message}")
                if (!r.ok) { message = r.message; break }
            }
            viewModel.voiceprintUploading.value = false
            viewModel.voiceprintMessage.value = message
        }
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
                File(entry.dir, SessionPaths.DIAGNOSTICS_LOG_FILE)
                    .takeIf { it.exists() }?.let { list.add(it) }
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
        if (RecordingController.link.value.isSessionActive) {
            appendWearableDebugLog("Warning: 采集服务正在使用同一设备，请先停止采集再测试。")
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