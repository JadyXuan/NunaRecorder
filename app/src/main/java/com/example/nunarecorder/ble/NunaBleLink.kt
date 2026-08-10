package com.example.nunarecorder.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.core.app.ActivityCompat
import com.example.nunarecorder.util.DiagnosticsLog
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 与 Nuna 设备的 BLE 链路：连接 → 服务发现 → A002 握手 → 订阅 A003 音频，
 * **断开后自动重连，直到调用方主动 [stop]**。
 *
 * 这是 2026-07-31 实测暴露的核心问题所在。原来的实现（`MainActivity.gattCallback` 与
 * `NunaWearableServiceImpl.onConnectionStateChange`）在 `STATE_DISCONNECTED` 时
 * 只是把录制标记清掉，没有任何重连；四个会话里最长的一个 29 分钟就断了，
 * 断点之后的时间既没有音频也没有记录。对 16 小时佩戴来说，断一次就丢掉一整天。
 *
 * 覆盖四种中断：
 * - **走出范围 / 链路超时**：GATT 回调 `status=8`，按 [ReconnectPolicy] 退避重试。
 * - **设备关机**：`status=19`（对端主动断开），同上。
 * - **手机蓝牙被关掉**：监听 [BluetoothAdapter.ACTION_STATE_CHANGED]，
 *   关掉时停止无谓的重试，重新打开时立刻重连并把退避清零。
 * - **链路名义上还在但设备不再推流**：[dataTimeoutMs] 看门狗强制重连。
 *   实测四个会话里有两个一帧音频都没收到，普通的断连回调抓不到这种。
 */
class NunaBleLink(
    private val context: Context,
    private val listener: Listener,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    /**
     * **出过流之后**停了多久算链路已死。设备正常时 50 帧/秒，停 45 秒一定不对。
     */
    private val dataTimeoutMs: Long = 45_000L,
    /**
     * 订阅成功后到**第一帧**的宽限时间。
     *
     * 这个必须比 [dataTimeoutMs] 宽得多。2026-08-08 八台设备实测出现大量
     * `no_audio_for_50s/57s`，而用户观察到多台设备「需要在充电板配对后连接后才可以采数据」——
     * 也就是设备连上之后要过一阵才出流。原来这里和"出过流又停了"共用 45 秒，
     * 于是我们在设备还没准备好时就掐掉重连，而重连又要重走整个握手，
     * **永远到不了出流那一刻**，成了自我挫败的循环。
     */
    private val firstDataGraceMs: Long = 150_000L,
    /** GATT 连上后多久还没订阅到 A003 就重来 */
    private val setupTimeoutMs: Long = 30_000L
) {

    interface Listener {
        fun onLinkLog(message: String)
        fun onGattConnected(deviceName: String)
        fun onAudioSubscribed()
        fun onDisconnected(reason: String)
        fun onReconnectScheduled(attempt: Int, delayMs: Long)
        fun onReconnecting()
        fun onAudioData(data: ByteArray)

        /** 设备电量 0–100；来自标准 BLE 电池服务 */
        fun onBatteryLevel(percent: Int)

        /** 设备固件版本，例如 `3.14.5.1813` */
        fun onFirmwareRevision(version: String)

        /** A001 上的毫米波原始包（envelope type 0x08），原样交给写入器 */
        fun onSensorPacket(raw: ByteArray) {}

        /** 毫米波开关状态变化（A001 0x13）。固件自己 30s 开 / 30s 关 */
        fun onRadarState(enabled: Boolean) {}

        /** 无法自行恢复（缺权限、找不到服务），需要人工介入 */
        fun onFatal(reason: String)
    }

    companion object {
        private const val TAG = "BleLink"
        private const val WATCHDOG_PERIOD_MS = 15_000L
        /** 连上到发起服务发现之间的等待 */
        private const val SERVICE_DISCOVERY_DELAY_MS = 600L
        /** close() 到下一次 connectGatt 之间的最小间隔 */
        private const val RECONNECT_SETTLE_MS = 1_200L
        /** discoverServices 发起后多久没回调就重来 */
        private const val SERVICE_DISCOVERY_TIMEOUT_MS = 10_000L
        /** A003 订阅后多久读第一次电量 */
        private const val BATTERY_FIRST_READ_DELAY_MS = 5_000L
        /** 订阅后多久还没有音频就 STOP→START 复位一次 */
        private const val RECORDING_RESTART_DELAY_MS = 8_000L
        /** 复位最多试几次；再多就是在给 A002 制造命令风暴 */
        private const val RECORDING_RESTART_MAX = 3
        /** 两次复位之间的间隔 */
        private const val RECORDING_RESTART_RETRY_MS = 20_000L
        /** 首帧音频之后多久订阅 A001 遥测；与电量/固件读错开 */
        private const val STATUS_SUBSCRIBE_DELAY_MS = 3_000L
        /** 两条 A002 控制命令之间的间隔，避开 GATT 的一次一个操作 */
        private const val RECORDING_CONTROL_GAP_MS = 600L
        /** 发完 STOP 到真正关闭 GATT 之间留的时间 */
        private const val STOP_SETTLE_MS = 250L
        /** 产品版 A003 通知约 501 字节，ATT MTU 要谈到接近 517 */
        private const val PREFERRED_ATT_MTU = 517
        /** 产品版音频包的建议下限，低于它只告警不阻断 */
        private const val PRODUCT_AUDIO_MIN_ATT_MTU = 504
        private const val MTU_TIMEOUT_MS = 2_000L
        /** 电量变化很慢，几分钟一次足够 */
        private const val BATTERY_POLL_PERIOD_MS = 5 * 60_000L

        private val SERVICE_UUID: UUID = UUID.fromString(ProtoConfig.Service.SERVICE_UUID)
        private val TRANSFER_CHAR_UUID: UUID = UUID.fromString(ProtoConfig.Service.TRANSFER_CHAR_UUID)
        private val STATUS_CHAR_UUID: UUID = UUID.fromString(ProtoConfig.Service.STATUS_CHAR_UUID)
        private val RECORDING_CHAR_UUID: UUID = UUID.fromString(ProtoConfig.Service.RECORDING_CHAR_UUID)
        private val CCCD_UUID: UUID = UUID.fromString(ProtoConfig.Service.CCCD_UUID)

        /**
         * 标准 BLE 电池服务。2026-08-06 实测设备确实暴露了它：
         * `0000180f` / `00002a19 [RN]`（可读 + 可订阅）。
         * A001 只返回 1 字节 `00`，不是电量。
         */
        private val BATTERY_SERVICE_UUID: UUID =
            UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_UUID: UUID =
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        /**
         * 标准 Device Information Service。2026-08-06 的 profile dump 里有 `0000180a`。
         *
         * 固件版本要进 manifest：2026-08-08 那次 01AF「用不了」，最后是升级固件
         * （1811 → 1813）解决的。也就是说**固件版本会决定一台设备能不能采到数据**，
         * 而数据里看不出来是哪个版本采的。
         */
        /**
         * **2026-08-09 关掉了固件读取。**
         *
         * 对照上传数据：app 1.8 每 60 秒的段收到 2880–2952 个包（设备帧 80–82 个），
         * 1.13 只收到 1476–1800（设备帧 41–50），而 `sequence_lost=0` / `gaps=[]`——
         * **不是丢包，是设备根本不发了**。1.8 → 1.13 之间我在 BLE 上只加了两样：
         * 看门狗拆分（只影响何时拆链路，不影响设备推流）和这一次固件 read。
         *
         * 它是诊断用的锦上添花，而采到数据是任务本身。设备固件版本换个方式也能拿到
         * （官方 App、或者以后单独一个不在采集期跑的探测包）。
         *
         * **2026-08-09 重新打开。** 对照数据已经证明它与掉数据无关：同一台手机、
         * 同一晚、同一个 app 1.8，没更新固件的 04DF 占空比 99%，更新过固件的 018A 是 0%。
         * 真正的原因是新固件要显式收 START、断开前要收 STOP（见 RecordingControlProtocol）。
         *
         * 协议文档里 A001 的 `0x05` 设备信息比标准 DIS 更准（带型号、硬件版本、
         * 序列号），但那要新增一条控制命令；先用 DIS 把固件版本拿回来。
         */
        private const val READ_FIRMWARE_ON_CONNECT = true

        private val DEVICE_INFO_SERVICE_UUID: UUID =
            UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        private val FIRMWARE_REVISION_UUID: UUID =
            UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")

        /** GATT status → 人能看懂的原因，写进 manifest 的 link.events[].reason */
        fun describeStatus(status: Int): String = when (status) {
            BluetoothGatt.GATT_SUCCESS -> "link_closed"
            8 -> "connection_timeout(8)"   // 走出范围最常见
            19 -> "peer_terminated(19)"    // 设备关机 / 主动断开
            22 -> "local_terminated(22)"
            133 -> "gatt_error(133)"
            else -> "gatt_status($status)"
        }
    }

    private val thread = HandlerThread("nuna-ble-link").apply { start() }
    private val handler = Handler(thread.looper)

    private val bluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private val handshakeClient = HandshakeClient(context) { msg -> log("HS", msg) }

    private var gatt: BluetoothGatt? = null
    private var deviceAddress: String? = null
    private val running = AtomicBoolean(false)
    private var reconnectAttempt = 0
    private var subscribed = false
    private var lastDataAtMs = 0L
    /** A003 订阅成功的时刻；宽限期从这里算 */
    private var subscribedAtMs = 0L
    /** 本次连接是否收到过哪怕一帧 */
    private var receivedAnyData = false
    private var connectedAtMs = 0L
    private var awaitingStatusRead = false
    private var adapterReceiverRegistered = false
    private var profileDumped = false
    private var servicesDiscovered = false
    /** 上一次 teardownGatt 的时刻；紧接着重连协议栈来不及清理 */
    private var lastTeardownAtMs = 0L

    /** 蓝牙被用户关掉：不要空转重连，等它回来 */
    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF -> handler.post {
                    if (!running.get()) return@post
                    log(TAG, "蓝牙已关闭，暂停重连并等待重新打开")
                    listener.onDisconnected("bluetooth_off")
                    teardownGatt()
                    handler.removeCallbacks(reconnectRunnable)
                }
                BluetoothAdapter.STATE_ON -> handler.post {
                    if (!running.get()) return@post
                    log(TAG, "蓝牙已重新打开，立即重连")
                    reconnectAttempt = 0
                    handler.removeCallbacks(reconnectRunnable)
                    handler.post(reconnectRunnable)
                }
            }
        }
    }

    private val reconnectRunnable = Runnable {
        if (!running.get()) return@Runnable
        listener.onReconnecting()
        connectNow()
    }

    /**
     * 两个静默失败靠断连回调永远抓不到，只能靠超时：
     * - 订阅成功了但设备不推流（实测四个会话里有两个一帧都没收到）
     * - 连上了但握手 / A001 read / A003 订阅卡在半路
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running.get()) return
            val now = System.currentTimeMillis()
            val dead = when {
                // 订阅了但一帧都还没来：给足宽限，别把还在启动的设备掐掉
                subscribed && !receivedAnyData && subscribedAtMs > 0L &&
                    now - subscribedAtMs > firstDataGraceMs ->
                    "no_first_audio_for_${(now - subscribedAtMs) / 1000}s"
                // 出过流又停了：这个是真的坏了
                subscribed && receivedAnyData && lastDataAtMs > 0L &&
                    now - lastDataAtMs > dataTimeoutMs ->
                    "audio_stalled_for_${(now - lastDataAtMs) / 1000}s"
                !subscribed && connectedAtMs > 0L && now - connectedAtMs > setupTimeoutMs ->
                    "subscribe_timeout_${(now - connectedAtMs) / 1000}s"
                else -> null
            }
            if (dead != null) {
                log(TAG, "看门狗判定链路已死（$dead），强制重连")
                listener.onDisconnected(dead)
                teardownGatt()
                scheduleReconnect()
            }
            handler.postDelayed(this, WATCHDOG_PERIOD_MS)
        }
    }

    // ── 对外 ───────────────────────────────────────────────────────────────

    fun start(address: String) {
        handler.post {
            if (running.getAndSet(true)) {
                log(TAG, "start 被重复调用，忽略")
                return@post
            }
            deviceAddress = address
            reconnectAttempt = 0
            registerAdapterReceiver()
            handler.postDelayed(watchdog, WATCHDOG_PERIOD_MS)
            connectNow()
        }
    }

    fun stop() {
        handler.post {
            if (!running.getAndSet(false)) return@post
            log(TAG, "stop：用户主动停止，取消所有重连")
            handler.removeCallbacks(reconnectRunnable)
            handler.removeCallbacks(watchdog)
            handler.removeCallbacks(batteryPoll)
            batteryGatt = null
            unregisterAdapterReceiver()
            // 先把 STOP 写出去，给协议栈一点时间，再真正关闭。
            // 用户主动停止是**唯一**能干净收尾的时机，别浪费它——
            // 这一步没做好，下一次采集就要靠充电板复位设备。
            sendRecordingStopBestEffort()
            handler.postDelayed({ teardownGatt() }, STOP_SETTLE_MS)
        }
    }

    /**
     * 停止并回收线程。
     *
     * **退出必须排在 teardown 之后。** 原来是 `stop()` 紧接 `handler.post { quitSafely() }`：
     * `stop()` 用 `postDelayed(teardownGatt, STOP_SETTLE_MS)` 先发 STOP 再关 GATT，
     * 而 **`quitSafely()` 会丢弃尚未到点的延时消息**——于是 `teardownGatt()` 从来没跑过，
     * GATT 一直连着。设备在已连接状态下不广播，所以停止采集之后"附近的设备"里
     * 再也扫不到它，只有杀掉进程才释放（用户 2026-08-10 实测："必须重启 app"）。
     *
     * 这是我 v1.16 加 STOP 命令时引入的回归。
     */
    fun release() {
        stop()
        handler.postDelayed({ thread.quitSafely() }, STOP_SETTLE_MS + 150L)
    }

    // ── 连接 ───────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun connectNow() {
        val address = deviceAddress ?: return
        if (!hasConnectPermission()) {
            listener.onFatal("缺少蓝牙连接权限，无法录制")
            running.set(false)
            return
        }
        val a = adapter
        if (a == null || !a.isEnabled) {
            log(TAG, "蓝牙未开启，等待其重新打开")
            listener.onDisconnected("bluetooth_off")
            return
        }
        teardownGatt()
        // 关闭和重新 connectGatt 之间必须留出时间，否则协议栈还在清理上一个客户端，
        // 新连接会拿到空的服务列表。
        val sinceTeardown = System.currentTimeMillis() - lastTeardownAtMs
        if (sinceTeardown in 0 until RECONNECT_SETTLE_MS) {
            handler.postDelayed({ if (running.get()) connectNow() }, RECONNECT_SETTLE_MS - sinceTeardown)
            return
        }
        subscribed = false
        awaitingStatusRead = false
        connectedAtMs = 0L
        lastDataAtMs = 0L
        subscribedAtMs = 0L
        receivedAnyData = false
        statusSubscribed = false
        lastRadarEnabled = null
        profileDumped = false
        servicesDiscovered = false
        // **一律 autoConnect=false。**
        //
        // 2026-08-06 我曾把重试改成 autoConnect=true，想解决"每次建链硬等 30 秒"。
        // 结果是服务发现彻底坏掉：连接照样瞬间成功，但 onServicesDiscovered 再也不来，
        // 只能等看门狗超时（三份日志对比得出——加它之前 14:08 那次能正常列出 8 个 service，
        // 加了之后 15:20 和 19:42 两次都没有一次成功）。
        //
        // 成因是 autoConnect=true 会在协议栈里留下后台待连请求，close() 之后可能残留；
        // 设备只接受一路 GATT 连接时，新连接在 ACL 层连上了但 GATT 操作全哑。
        // 建链慢是可以忍的，服务发现哑掉不行。
        val autoConnect = false
        connectionGeneration++
        log(TAG, "connectGatt → $address（第 ${reconnectAttempt + 1} 次尝试，第 $connectionGeneration 代）")
        val device = try {
            a.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            listener.onFatal("设备地址无效：$address")
            running.set(false)
            return
        }
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(
                context, autoConnect, gattCallback,
                android.bluetooth.BluetoothDevice.TRANSPORT_LE
            )
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, autoConnect, gattCallback)
        }
        if (gatt == null) {
            log(TAG, "connectGatt 返回 null")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!running.get()) return
        reconnectAttempt++
        // autoConnect=true 之后协议栈自己在等设备广播，我们的退避只是兜底，
        // 不需要再拉到 30 秒——那只会让"设备刚好回来"时白等一轮。
        val delay = policy.delayForAttempt(reconnectAttempt)
        log(TAG, "第 $reconnectAttempt 次重连将在 ${delay / 1000} 秒后进行")
        listener.onReconnectScheduled(reconnectAttempt, delay)
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delay)
    }

    @SuppressLint("MissingPermission")
    /** MTU 协商是否已经有结论（回调或超时），避免服务发现被发起两次 */
    private var mtuSettled = false
    private var negotiatedMtu = 23

    @SuppressLint("MissingPermission")
    private fun startServiceDiscovery(g: BluetoothGatt, gen: Int, reason: String) {
        if (mtuSettled) return
        mtuSettled = true
        if (isStale(g) || !running.get()) return
        val ok = runCatching { g.discoverServices() }.getOrDefault(false)
        log(TAG, "发起服务发现（第 $gen 代，$reason，MTU=$negotiatedMtu）= $ok")
        if (!ok) {
            teardownGatt()
            scheduleReconnect()
        }
    }

    private fun teardownGatt() {
        val g = gatt ?: return
        // 断开前把 STOP 发出去。不发就断，产品版固件的状态机会卡在录音态，
        // 下次重连订阅上了也不推流——我们此前每次 teardown 都在制造这个问题。
        sendRecordingStopBestEffort()
        handler.removeCallbacks(recordingRestart)
        restartGatt = null
        gatt = null
        subscribed = false
        lastTeardownAtMs = System.currentTimeMillis()
        try {
            g.disconnect()
            g.close()
        } catch (_: Exception) {
        }
    }

    // ── GATT 回调 ──────────────────────────────────────────────────────────

    /**
     * 所有 GATT 实例共用同一个 callback 对象，所以**必须**按实例身份过滤。
     *
     * 2026-08-06 实测（vivo V2303A / Android 16）：`teardownGatt()` 关掉旧连接后，
     * 它迟到的 `onConnectionStateChange(DISCONNECTED)` 和 `onServicesDiscovered`
     * 才到达，被当成当前链路的事件处理——于是刚建好的新连接被拆掉
     * （日志里连上 32 ms 后就 `local_terminated(22)`），并触发又一次重连，无限循环。
     * 还有一次连上仅 12 ms 就"发现完服务"且一个都没找到，同样是旧实例的回调。
     */
    private fun isStale(g: BluetoothGatt): Boolean = g !== gatt

    /**
     * 每次连接递增。回调里带上它，日志就能直接看出"这条回调属于第几次连接"，
     * 而不必靠对比三份日志去猜。
     */
    private var connectionGeneration = 0

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val gen = connectionGeneration
            handler.post {
                if (isStale(g)) {
                    log(TAG, "丢弃旧连接的状态回调（第 $gen 代）status=$status newState=$newState")
                    return@post
                }
                if (!running.get()) {
                    // 用户已经停止，这是我们自己 disconnect() 引发的迟到回调
                    return@post
                }
                if (newState == BluetoothProfile.STATE_CONNECTED &&
                    status == BluetoothGatt.GATT_SUCCESS
                ) {
                    val gen = connectionGeneration
                    reconnectAttempt = 0
                    connectedAtMs = System.currentTimeMillis()
                    val name = try {
                        g.device?.name ?: g.device?.address ?: "unknown"
                    } catch (_: SecurityException) {
                        "unknown"
                    }
                    // **立刻发起服务发现，不再延迟。**
                    // 我曾加过 600 ms 延迟，想躲开"拿到缓存的空列表"。但 2026-08-06 的
                    // 日志显示延迟之后 onServicesDiscovered 一次都没回来过，而加延迟之前
                    // （14:08 那份日志）是能正常列出 8 个 service 的。
                    // 那个"连上 12 ms 就发现完且一个都没有"的现象另有其因——是旧实例的
                    // 回调，已经由 isStale 守卫解决，不需要用延迟去躲。
                    // 产品版 A003 通知约 501 字节，默认 23 的 ATT MTU 装不下。
                    // 协议文档（Ruihan 实机验证）要求先协商到接近 517。
                    // 回调不来就超时继续——MTU 谈不成也比卡在这里强。
                    mtuSettled = false
                    val mtuQueued = runCatching { g.requestMtu(PREFERRED_ATT_MTU) }.getOrDefault(false)
                    log(TAG, "已连接 $name（第 $gen 代），requestMtu($PREFERRED_ATT_MTU) = $mtuQueued")
                    if (!mtuQueued) {
                        startServiceDiscovery(g, gen, "mtu_not_queued")
                    } else {
                        handler.postDelayed({
                            if (!mtuSettled) startServiceDiscovery(g, gen, "mtu_timeout")
                        }, MTU_TIMEOUT_MS)
                    }
                    listener.onGattConnected(name)
                    handler.postDelayed({
                        if (isStale(g)) {
                            log(TAG, "第 $gen 代的发现超时检查被跳过：连接已被替换")
                            return@postDelayed
                        }
                        if (!running.get() || servicesDiscovered) return@postDelayed
                        log(TAG, "第 $gen 代服务发现 ${SERVICE_DISCOVERY_TIMEOUT_MS / 1000} 秒无回调，重连重试")
                        listener.onDisconnected("service_discovery_timeout")
                        teardownGatt()
                        scheduleReconnect()
                    }, SERVICE_DISCOVERY_TIMEOUT_MS)
                    return@post
                }
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val reason = describeStatus(status)
                    log(TAG, "链路断开：$reason")
                    listener.onDisconnected(reason)
                    teardownGatt()
                    if (adapter?.isEnabled == true) scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val count = runCatching { g.services.size }.getOrDefault(-1)
            handler.post {
                // 先无条件记一笔：之前这里被守卫静默吃掉，日志上表现为"回调根本没来"，
                // 害我对着三份日志猜了四轮。任何丢弃都必须留痕。
                log(TAG, "onServicesDiscovered status=$status 共 $count 个 service")
                if (isStale(g)) {
                    log(TAG, "但它属于已被替换的连接，丢弃")
                    return@post
                }
                if (!running.get()) return@post
                servicesDiscovered = true
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log(TAG, "服务发现失败 status=$status")
                    teardownGatt()
                    scheduleReconnect()
                    return@post
                }
                dumpGattProfile(g)
                // **这里绝对不要碰电量特征。**
                // Android GATT 同一时刻只允许一个未完成操作。我曾在这里调
                // subscribeBattery()，它的 writeDescriptor 紧挨着下面 A002 的
                // writeDescriptor，第二个直接被丢弃 —— A002 通知从未启用，
                // 握手永远不会开始，界面就永远停在"正在握手"。
                // 电量推迟到音频链路完全建立之后再读，见 scheduleBatteryRead()。
                val transferChar = g.getService(SERVICE_UUID)?.getCharacteristic(TRANSFER_CHAR_UUID)
                if (transferChar == null) {
                    // 服务列表不完整（协议栈缓存或发现被打断）。重来一次通常就好了，
                    // 不是设备真的没有这个特征。
                    log(TAG, "服务列表里没有 A002（共 ${g.services.size} 个 service），重连重试")
                    listener.onDisconnected("incomplete_service_discovery")
                    teardownGatt()
                    scheduleReconnect()
                    return@post
                }
                g.setCharacteristicNotification(transferChar, true)
                val cccd = transferChar.getDescriptor(CCCD_UUID)
                if (cccd == null) {
                    log(TAG, "A002 未找到 CCCD")
                    teardownGatt()
                    scheduleReconnect()
                    return@post
                }
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                negotiatedMtu = mtu
                log(TAG, "MTU 协商结果 $mtu（status=$status）")
                if (mtu < PRODUCT_AUDIO_MIN_ATT_MTU) {
                    // 只告警不阻断：老固件的包很小，MTU 谈不上去照样能采
                    log(TAG, "MTU $mtu 低于产品版 501 字节音频包的建议值 $PRODUCT_AUDIO_MIN_ATT_MTU，仍继续")
                }
                startServiceDiscovery(g, connectionGeneration, "mtu_changed")
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            handler.post {
                if (!running.get()) return@post
                if (descriptor.uuid != CCCD_UUID) return@post
                @Suppress("DEPRECATION")
                val charUuid = descriptor.characteristic?.uuid
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log(TAG, "CCCD 写入失败 char=$charUuid status=$status")
                    teardownGatt()
                    scheduleReconnect()
                    return@post
                }
                when (charUuid) {
                    TRANSFER_CHAR_UUID -> {
                        log(TAG, "A002 通知已启用，开始握手")
                        handshakeClient.startHandshake(g)
                    }
                    RECORDING_CHAR_UUID -> {
                        subscribed = true
                        subscribedAtMs = System.currentTimeMillis()
                        receivedAnyData = false
                        lastDataAtMs = subscribedAtMs
                        recordingRestartCount = 0
                        // 产品版固件必须显式发 START 才推流；老固件订阅就推，
                        // 多发这一条也无害。见 RecordingControlProtocol。
                        handshakeClient.requestRecordingControl(g, enabled = true)
                        // 音频起来了再管电量，且延迟几秒，彻底避开订阅阶段的操作槽
                        scheduleBatteryRead(g)
                        scheduleRecordingRestart(g)
                        log(TAG, "A003 已订阅，已发录音开始命令，等待音频")
                        listener.onAudioSubscribed()
                    }
                }
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            logStatusPayload(characteristic, value)
            reportBattery(characteristic, value)
            reportFirmware(characteristic, value)
            handleStatusRead(g, characteristic)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            logStatusPayload(characteristic, characteristic.value ?: ByteArray(0))
            handleStatusRead(g, characteristic)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                TRANSFER_CHAR_UUID -> handler.post {
                    if (running.get()) handshakeClient.onNotification(g, characteristic)
                }
                BATTERY_LEVEL_UUID -> handler.post { reportBattery(characteristic, value) }
                // A001 遥测：毫米波原始包、雷达开关、设备信息。**直接在 BLE 线程处理**，
                // 不绕主线程——毫米波包频率高，绕一圈只会给主线程添堵。
                STATUS_CHAR_UUID -> dispatchStatusNotification(value)
                RECORDING_CHAR_UUID -> {
                    if (!receivedAnyData) {
                        receivedAnyData = true
                        handler.removeCallbacks(recordingRestart)
                        // **音频起来之后**才订阅 A001 遥测（毫米波 0x08、雷达开关 0x13、
                        // 设备信息 0x05 都走这一路）。绝不插进握手链路：
                        // 2026-08-06 卡死三天就是一次多余的 CCCD 写入挤掉了 A002 握手。
                        // 这里失败最多丢遥测，音频已经在流了。
                        scheduleStatusSubscribe(g)
                        log(TAG, "收到第一帧音频（订阅后 ${(System.currentTimeMillis() - subscribedAtMs) / 1000} 秒）")
                    }
                    // 音频走热路径：不 post 到 handler，直接交给上层写文件。
                    // 50 帧/秒 × 一次 post 的排队开销在 16 小时里是笔真钱。
                    lastDataAtMs = System.currentTimeMillis()
                    if (running.get()) listener.onAudioData(value)
                }
            }
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val value = characteristic.value ?: return
            onCharacteristicChanged(g, characteristic, value)
        }
    }

    /**
     * A001（"设备信息 / 状态上报"）的返回值原来被直接丢掉。电量、固件版本这类信息
     * 最可能就在这里，而我们没有设备侧文档，只能把原始字节打出来自己解。
     * 十几个字节，不含任何可识别内容，可以安全写进导出日志。
     */
    private fun logStatusPayload(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != STATUS_CHAR_UUID) return
        log(TAG, "[STATUS] A001 读到 ${value.size} 字节: " + value.joinToString(" ") { "%02X".format(it) })
    }

    /**
     * 握手成功后 [HandshakeClient] 会 read A001 触发设备开始录音。
     * A003 的 CCCD 必须等这次 read 回来再写——GATT 一次只允许一个未完成操作，
     * 抢着写 `writeDescriptor` 会直接返回 false。
     */
    @SuppressLint("MissingPermission")
    private fun handleStatusRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (characteristic.uuid != STATUS_CHAR_UUID) return
        handler.post {
            if (!running.get() || awaitingStatusRead) return@post
            awaitingStatusRead = true
            enableAudioNotifications(g)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableAudioNotifications(g: BluetoothGatt) {
        val recordingChar = g.getService(SERVICE_UUID)?.getCharacteristic(RECORDING_CHAR_UUID)
        if (recordingChar == null) {
            log(TAG, "未找到 A003 音频特征")
            teardownGatt()
            scheduleReconnect()
            return
        }
        g.setCharacteristicNotification(recordingChar, true)
        val cccd = recordingChar.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            log(TAG, "A003 未找到 CCCD")
            teardownGatt()
            scheduleReconnect()
            return
        }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        if (!g.writeDescriptor(cccd)) {
            log(TAG, "A003 CCCD 写入未入队，150ms 后重试")
            handler.postDelayed({
                if (!running.get()) return@postDelayed
                @Suppress("DEPRECATION")
                if (!g.writeDescriptor(cccd)) {
                    log(TAG, "A003 CCCD 重试仍失败，重连")
                    teardownGatt()
                    scheduleReconnect()
                }
            }, 150)
        }
    }

    /**
     * 把设备暴露的所有 service / characteristic 打进诊断日志。
     *
     * 我们没有设备侧的协议文档，电量、固件版本、设备状态到底在哪个特征上只能自己查。
     * 一次连接打一次，几十行，对 5000 行的环形缓冲可以忽略。
     * 标准电池服务是 0x180F / 0x2A19，如果设备实现了，这里一眼能看到。
     */
    @SuppressLint("MissingPermission")
    private fun dumpGattProfile(g: BluetoothGatt) {
        if (profileDumped) return
        profileDumped = true
        val services = try {
            g.services
        } catch (_: SecurityException) {
            return
        }
        log(TAG, "[PROFILE] 共 ${services.size} 个 service")
        for (svc in services) {
            log(TAG, "[PROFILE] service ${svc.uuid}")
            for (ch in svc.characteristics) {
                val p = ch.properties
                val flags = buildString {
                    if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) append("R")
                    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) append("W")
                    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) append("w")
                    if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append("N")
                    if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append("I")
                }
                log(TAG, "[PROFILE]   char ${ch.uuid} [$flags]")
            }
        }
    }

    /**
     * 音频链路建立之后再定期读一次电量。
     *
     * **只用 read，不订阅、不写描述符。** 订阅电量要写 CCCD，而 CCCD 写入会和
     * 握手/音频订阅抢同一个 GATT 操作槽——2026-08-06 到 08-07 我就是这么把握手
     * 卡死了三天：电量的 writeDescriptor 紧挨着 A002 的 writeDescriptor，
     * 后者被静默丢弃，A002 通知从未启用。
     *
     * 电量变化很慢，几分钟读一次完全够，不值得为它冒险动 CCCD。
     */
    @SuppressLint("MissingPermission")
    /** 本次订阅已经试过几次 STOP→START 复位 */
    private var recordingRestartCount = 0
    private var restartGatt: BluetoothGatt? = null

    /**
     * 订阅上了却收不到音频时，先 STOP 再 START 把设备状态机踢一下。
     *
     * Ruihan 2026-08-09 实机验证：产品版固件如果上次是直接断开 GATT 而没发 STOP，
     * 状态机会卡住，下次重连订阅了也不推流；先发 STOP 再发 START 就出音频。
     * 这正是我们看到的"已订阅但没有收到数据"，也是为什么必须放回充电板
     * 进配对模式才能采到数据——那是在用别的方式复位设备。
     *
     * 只试一次。试不好就交给看门狗重连，别把 A002 变成命令风暴。
     */
    private val recordingRestart: Runnable = Runnable {
        val g = restartGatt ?: return@Runnable
        if (!running.get() || !subscribed || receivedAnyData || isStale(g)) return@Runnable
        recordingRestartCount++
        log(TAG, "订阅后仍无音频，第 $recordingRestartCount 次 STOP→START 复位设备状态机")
        handshakeClient.requestRecordingControl(g, enabled = false)
        // 两条命令都写 A002，必须错开：GATT 一次只允许一个未完成操作
        handler.postDelayed({
            if (running.get() && subscribed && !receivedAnyData && !isStale(g)) {
                handshakeClient.requestRecordingControl(g, enabled = true)
                // 一次不够。2026-08-10 户外实测反复出现 no_first_audio_for_16xs：
                // 复位只试一次，不成就干等看门狗到 150 秒再拆链路重连，
                // 而重连之后又是同样的一次。多试几次比多重连几轮便宜得多。
                if (recordingRestartCount < RECORDING_RESTART_MAX) {
                    handler.postDelayed(recordingRestart, RECORDING_RESTART_RETRY_MS)
                } else {
                    log(TAG, "STOP→START 已试 $recordingRestartCount 次仍无音频，交给看门狗")
                }
            }
        }, RECORDING_CONTROL_GAP_MS)
    }

    private fun scheduleRecordingRestart(g: BluetoothGatt) {
        restartGatt = g
        handler.removeCallbacks(recordingRestart)
        if (recordingRestartCount >= RECORDING_RESTART_MAX) return
        handler.postDelayed(recordingRestart, RECORDING_RESTART_DELAY_MS)
    }

    /**
     * 主动断开前把 STOP 发出去。
     *
     * **不发 STOP 就断开会让设备状态机卡住，下次重连收不到音频**——这是产品版固件
     * 的行为，我们此前每次 teardown 都在制造这个问题。这里是 best-effort：
     * 写入是 WRITE_NO_RESPONSE，发完给协议栈一点时间再真正关闭。
     */
    private fun sendRecordingStopBestEffort() {
        val g = gatt ?: return
        if (!subscribed) return
        runCatching { handshakeClient.requestRecordingControl(g, enabled = false) }
    }

    private var statusSubscribed = false

    /**
     * 订阅 A001 状态通知。毫米波原始包、雷达开关、设备信息全走这一条。
     *
     * 延后 [STATUS_SUBSCRIBE_DELAY_MS] 再发，和电量/固件读错开——
     * GATT 一次只允许一个未完成操作，挤在一起后发的会被静默丢弃。
     */
    @SuppressLint("MissingPermission")
    private fun scheduleStatusSubscribe(g: BluetoothGatt) {
        if (statusSubscribed) return
        handler.postDelayed({
            if (!running.get() || isStale(g) || statusSubscribed) return@postDelayed
            val ch = g.getService(SERVICE_UUID)?.getCharacteristic(STATUS_CHAR_UUID)
            if (ch == null) {
                log(TAG, "没有 A001 特征，毫米波与设备信息不可用")
                return@postDelayed
            }
            statusSubscribed = true
            runCatching {
                g.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(CCCD_UUID)
                if (cccd == null) {
                    log(TAG, "A001 没有 CCCD，遥测不可用（不影响音频）")
                    return@runCatching
                }
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION") g.writeDescriptor(cccd)
                }
                log(TAG, "已请求订阅 A001 遥测（毫米波 / 雷达开关 / 设备信息）")
            }.onFailure { log(TAG, "订阅 A001 失败：${it.javaClass.simpleName}（不影响音频）") }
        }, STATUS_SUBSCRIBE_DELAY_MS)
    }

    /**
     * A001 通知的分发。**任何异常都不能冒泡到音频路径**，所以整体包在 runCatching 里。
     */
    private fun dispatchStatusNotification(value: ByteArray) {
        runCatching {
            val env = NunaProtocolInspector.parseEnvelope(value) ?: return@runCatching
            when (env.type) {
                0x08 -> listener.onSensorPacket(value)
                0x13 -> {
                    val enabled = env.data.firstOrNull()?.toInt() == 1
                    if (enabled != lastRadarEnabled) {
                        lastRadarEnabled = enabled
                        log(TAG, "毫米波${if (enabled) "开启" else "关闭"}")
                        listener.onRadarState(enabled)
                    }
                }
                0x05 -> {
                    // 设备信息里的固件版本才是权威的。标准 DIS 的 0x2A26 在实测中
                    // 返回 1.0.0，那是个通用串，不是 3.14.5.1813。
                    val fw = NunaProtocolInspector.parseStatus(env).fields["firmware"] as? String
                    if (!fw.isNullOrBlank()) {
                        log(TAG, "设备信息固件版本 $fw")
                        listener.onFirmwareRevision(fw)
                    }
                }
            }
        }
    }

    private var lastRadarEnabled: Boolean? = null

    private fun scheduleBatteryRead(g: BluetoothGatt) {
        handler.removeCallbacks(batteryPoll)
        batteryGatt = g
        firmwareRead = false
        handler.postDelayed(batteryPoll, BATTERY_FIRST_READ_DELAY_MS)
    }

    private var batteryGatt: BluetoothGatt? = null
    private var firmwareRead = false

    private val batteryPoll = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            if (!running.get()) return
            val g = batteryGatt
            // 只在音频确实在流的时候读，避免和重连期间的操作撞车
            if (g != null && !isStale(g) && subscribed) {
                // 固件只读一次，且和电量**错开**：GATT 一次只允许一个未完成操作，
                // 两个 read 挤在一起后发的会被丢弃（这正是 08-06 握手卡死的成因）。
                if (!firmwareRead && READ_FIRMWARE_ON_CONNECT) {
                    val fw = g.getService(DEVICE_INFO_SERVICE_UUID)
                        ?.getCharacteristic(FIRMWARE_REVISION_UUID)
                    if (fw != null) {
                        firmwareRead = true
                        runCatching { g.readCharacteristic(fw) }
                        // 错开一轮就够了，**不能**等满一个轮询周期：那会把首次电量
                        // 从 5 秒推到 5 分零 5 秒。错峰是为了让上一个 read 先完成，
                        // 不是为了省电——5 秒远超一次 GATT read 的耗时。
                        handler.postDelayed(this, BATTERY_FIRST_READ_DELAY_MS)
                        return
                    }
                    firmwareRead = true
                    log(TAG, "设备没有 Device Information Service，读不到固件版本")
                }
                val ch = g.getService(BATTERY_SERVICE_UUID)?.getCharacteristic(BATTERY_LEVEL_UUID)
                if (ch == null) {
                    log(TAG, "设备没有标准电池服务，电量不可用")
                    return
                }
                runCatching { g.readCharacteristic(ch) }
            }
            handler.postDelayed(this, BATTERY_POLL_PERIOD_MS)
        }
    }

    private fun reportFirmware(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != FIRMWARE_REVISION_UUID || value.isEmpty()) return
        val version = String(value, Charsets.UTF_8).trim().trim('\u0000')
        if (version.isEmpty()) return
        log(TAG, "设备固件版本 $version")
        listener.onFirmwareRevision(version)
    }

    private fun reportBattery(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != BATTERY_LEVEL_UUID || value.isEmpty()) return
        val percent = value[0].toInt() and 0xFF
        if (percent !in 0..100) return
        log(TAG, "设备电量 $percent%")
        listener.onBatteryLevel(percent)
    }

    private fun hasConnectPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
        return ActivityCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun registerAdapterReceiver() {
        if (adapterReceiverRegistered) return
        context.registerReceiver(
            adapterReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )
        adapterReceiverRegistered = true
    }

    private fun unregisterAdapterReceiver() {
        if (!adapterReceiverRegistered) return
        runCatching { context.unregisterReceiver(adapterReceiver) }
        adapterReceiverRegistered = false
    }

    private fun log(tag: String, message: String) {
        DiagnosticsLog.log(tag, message)
        listener.onLinkLog(message)
    }
}
