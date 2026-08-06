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
    /** 订阅成功后多久没收到音频就判定链路已死 */
    private val dataTimeoutMs: Long = 45_000L,
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
                subscribed && lastDataAtMs > 0L && now - lastDataAtMs > dataTimeoutMs ->
                    "no_audio_for_${(now - lastDataAtMs) / 1000}s"
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
            unregisterAdapterReceiver()
            teardownGatt()
        }
    }

    fun release() {
        stop()
        handler.post { thread.quitSafely() }
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
    private fun teardownGatt() {
        val g = gatt ?: return
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
                    val started = g.discoverServices()
                    log(TAG, "已连接 $name（第 $gen 代），discoverServices = $started")
                    listener.onGattConnected(name)
                    if (!started) {
                        teardownGatt()
                        scheduleReconnect()
                        return@post
                    }
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
                subscribeBattery(g)
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
                        lastDataAtMs = System.currentTimeMillis()
                        g.getService(BATTERY_SERVICE_UUID)
                            ?.getCharacteristic(BATTERY_LEVEL_UUID)
                            ?.let { runCatching { g.readCharacteristic(it) } }
                        log(TAG, "A003 已订阅，等待音频")
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
                RECORDING_CHAR_UUID -> {
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
     * 订阅设备电量。读一次拿初值，再开 notify 跟踪变化。
     * 失败不影响采集——电量只是辅助信息，不能让它挡住音频链路。
     */
    @SuppressLint("MissingPermission")
    private fun subscribeBattery(g: BluetoothGatt) {
        val ch = g.getService(BATTERY_SERVICE_UUID)?.getCharacteristic(BATTERY_LEVEL_UUID)
        if (ch == null) {
            log(TAG, "设备没有标准电池服务，电量不可用")
            return
        }
        runCatching {
            g.setCharacteristicNotification(ch, true)
            ch.getDescriptor(CCCD_UUID)?.let { cccd ->
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }
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
