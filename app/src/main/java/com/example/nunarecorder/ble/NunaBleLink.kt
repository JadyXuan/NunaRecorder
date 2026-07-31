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

        /** 无法自行恢复（缺权限、找不到服务），需要人工介入 */
        fun onFatal(reason: String)
    }

    companion object {
        private const val TAG = "BleLink"
        private const val WATCHDOG_PERIOD_MS = 15_000L

        private val SERVICE_UUID: UUID = UUID.fromString(ProtoConfig.Service.SERVICE_UUID)
        private val TRANSFER_CHAR_UUID: UUID = UUID.fromString(ProtoConfig.Service.TRANSFER_CHAR_UUID)
        private val STATUS_CHAR_UUID: UUID = UUID.fromString(ProtoConfig.Service.STATUS_CHAR_UUID)
        private val RECORDING_CHAR_UUID: UUID = UUID.fromString(ProtoConfig.Service.RECORDING_CHAR_UUID)
        private val CCCD_UUID: UUID = UUID.fromString(ProtoConfig.Service.CCCD_UUID)

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
        subscribed = false
        awaitingStatusRead = false
        connectedAtMs = 0L
        lastDataAtMs = 0L
        log(TAG, "connectGatt → $address（第 ${reconnectAttempt + 1} 次尝试）")
        val device = try {
            a.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            listener.onFatal("设备地址无效：$address")
            running.set(false)
            return
        }
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, gattCallback)
        }
        if (gatt == null) {
            log(TAG, "connectGatt 返回 null")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!running.get()) return
        reconnectAttempt++
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
        try {
            g.disconnect()
            g.close()
        } catch (_: Exception) {
        }
    }

    // ── GATT 回调 ──────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (!running.get()) {
                    // 用户已经停止，这是我们自己 disconnect() 引发的迟到回调
                    return@post
                }
                if (newState == BluetoothProfile.STATE_CONNECTED &&
                    status == BluetoothGatt.GATT_SUCCESS
                ) {
                    reconnectAttempt = 0
                    connectedAtMs = System.currentTimeMillis()
                    val name = try {
                        g.device?.name ?: g.device?.address ?: "unknown"
                    } catch (_: SecurityException) {
                        "unknown"
                    }
                    log(TAG, "已连接 $name，开始服务发现")
                    listener.onGattConnected(name)
                    if (!g.discoverServices()) {
                        log(TAG, "discoverServices 启动失败")
                        teardownGatt()
                        scheduleReconnect()
                    }
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
            handler.post {
                if (!running.get()) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log(TAG, "服务发现失败 status=$status")
                    teardownGatt()
                    scheduleReconnect()
                    return@post
                }
                val transferChar = g.getService(SERVICE_UUID)?.getCharacteristic(TRANSFER_CHAR_UUID)
                if (transferChar == null) {
                    log(TAG, "未找到 A002 握手特征")
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
        ) = handleStatusRead(g, characteristic)

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
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
