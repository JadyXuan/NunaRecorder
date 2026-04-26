package com.example.wearable.impl

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import android.content.pm.PackageManager
import android.Manifest
import com.example.wearable.AudioChunkProvider
import com.example.wearable.TranscriptionProvider
import com.example.wearable.WearableRecordingController
import com.example.wearable.data.AudioChunk
import com.example.wearable.internal.DeepgramFluxClient
import com.example.wearable.internal.ControlCommand
import com.example.wearable.internal.HandshakeMessageType
import com.example.wearable.internal.MessagePacker
import com.example.wearable.internal.MessageType
import com.example.wearable.WearableConnectionConfig
import com.example.wearable.internal.WearableBleConfig
import org.concentus.OpusDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 实现 [WearableRecordingController]、[AudioChunkProvider]、[TranscriptionProvider]。
 * 流程：startRecording → 用配置连接 Nuna 设备 → 握手 → 开启 A003 订阅 → onReady；
 * 订阅后 A003 推送 Opus，解码后 mono PCM 同时：按 chunk 转 WAV 经 [AudioChunkProvider] 输出；
 * 若配置 [WearableConnectionConfig.deepgramApiKey] 且调用了 [startTranscription]，则 tee 连续 PCM 送 Deepgram listen-flux，按句回调 [TranscriptionListener.onTranscriptionReady]。
 *
 * 连接配置：通过 [setConnectionConfig] 或 [setDeviceAddress] 在代码中指定设备，不扫描。
 */
class NunaWearableServiceImpl(
    private val context: Context
) : WearableRecordingController, AudioChunkProvider, TranscriptionProvider {

    companion object {
        private const val TAG = "NunaWearableSvc"
        /** 调试用：Logcat 过滤 WearableDebug；与 NunaRecorder 的 appendLog 分离 */
        const val DEBUG_TAG = "WearableDebug"
        private fun debugLog(msg: String) {
            Log.d(DEBUG_TAG, msg)
        }
    }

    private val bluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager.adapter }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var connectionConfig: WearableConnectionConfig? = null

    @Volatile
    private var deviceAddress: String? = null

    private val recording = AtomicBoolean(false)
    private var gatt: BluetoothGatt? = null
    private var onReady: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    private var handshakeVerificationCode: String? = null
    private var nextCommandId: Int = 0x2d01

    private var audioChunkListener: AudioChunkProvider.AudioChunkListener? = null
    @Volatile
    private var transcriptionListener: TranscriptionProvider.TranscriptionListener? = null

    /** 转写：Deepgram listen-flux WebSocket，与 chunk 共享解码路径，tee 连续 PCM 发送 */
    private val transcriptionActive = AtomicBoolean(false)
    @Volatile
    private var deepgramFluxClient: DeepgramFluxClient? = null
    /** 转写流首样本墙钟（用于将 TurnInfo 的 audio_window_start/end 转为 ms） */
    @Volatile
    private var transcriptionStreamStartWallMs: Long = 0
    private var transcriptionStreamStartWallMsSet = false
    /** 与 pcmMonoBuffer 同源 tee，仅当 transcriptionActive 时写入，每轮 drain 后发送并清空 */
    private val transcriptionPcmBuffer = ByteArrayOutputStream(64 * 1024)

    /** 默认 10s 块、2s 重叠（对接文档可改为 60s/10s） */
    private var chunkDurationMs: Long = 10_000
    private var overlapDurationMs: Long = 2_000
    private var chunkDeliveryActive = AtomicBoolean(false)
    private var chunkExecutor: ExecutorService? = null

    /** 旧逻辑：按 frameId 窗口凑 chunk；新逻辑优先用 bleStreamReassembler */
    private val opusFrameBuffer = mutableMapOf<Int, ByteArray>()
    private var recordingStartTimeMs: Long = 0
    private val bufferLock = Any()

    /**
     * 与 [com.example.nunarecorder.ble.BleAudioReassembler] 同解析；重组结果按 writeFrameToFile 顺序追加。
     * 实时按 80 字节 Opus 包解码 → mono PCM 时间轴；满 [chunkDurationMs] 即封 WAV 回调，
     * 下一窗起点前移 [chunkDurationMs − overlapDurationMs]，保证固定 ms 重叠。
     */
    private val bleStreamReassembler = BleStreamOpusReassembler(
        onAppendFrame = { concatenated -> onBleStreamFrameAppended(concatenated) }
    )
    private val bleStreamLock = Any()
    /** 与文件录制相同的 opus 裸流累积（可选观测） */
    private val opusStreamBuffer = ByteArrayOutputStream(256 * 1024)
    /** 未满 80 字节的尾部，与下次 append 拼接再解码 */
    private val opusPendingBuffer = ByteArrayOutputStream(4096)
    /** 解码得到的 mono 16-bit PCM 时间轴（16 kHz） */
    private val pcmMonoBuffer = ByteArrayOutputStream(2 * 1024 * 1024)
    @Volatile
    private var streamOpusDecoder: OpusDecoder? = null
    /** PCM 时间轴起点墙钟（首样本对应时刻） */
    private var pcmTimelineBaseWallMs: Long = 0
    /** 已交付块数，用于日志与文件名 */
    private var pcmChunkDeliveryIndex = 0

    /**
     * 设备侧 frameId 不一定从 0 开始（如从 5218 起）。Chunk 窗口按「相对帧序号」计算：
     * startFrameId = chunkBaseFrameId + windowStartMs/20，否则永远 mapNotNull 为空。
     */
    @Volatile
    private var chunkBaseFrameId: Int? = null

    /** A003 原始 notify 次数（用于确认是否有数据传入，日志节流） */
    private val a003NotifyCount = AtomicInteger(0)
    private val a003NotifyBytes = AtomicLong(0)
    private var a003FirstLogged = false

    /** 完整 Opus 帧计数（重组完成后） */
    private val opusFrameCompleteCount = AtomicInteger(0)

    /**
     * STEP3：已发起 A001 read，需在 onCharacteristicRead 之后再写 A003 CCCD，
     * 否则 writeDescriptor 易因 GATT 忙返回 false。
     */
    private val step3AwaitingA001Read = AtomicBoolean(false)

    /** 已弃用主路径：A003 现由 bleStreamReassembler 按 BleAudioReassembler 逻辑解析 */
    private val reassembler = OpusFrameReassembler { _, _ -> }

    /**
     * 为 true 时交付 overlap chunk 同时写入 wearable_wav_debug/overlap_*.wav；
     * 为 false 时仅 onAudioChunkReady，不写文件。不可直接暴露 var，否则与 [setDumpOverlapWavToDebugDir] JVM 签名冲突。
     */
    @Volatile
    private var dumpOverlapWavToDebugDir: Boolean = false

    /** 使用完整连接配置（推荐，可在代码中写死设备地址与校验码）。 */
    fun setConnectionConfig(config: WearableConnectionConfig?) {
        connectionConfig = config
        if (config != null) {
            dumpOverlapWavToDebugDir = config.dumpOverlapWavToDebugDir
        }
    }

    /** 仅设置设备地址时，握手校验码使用默认 "123456"；保留当前 [dumpOverlapWavToDebugDir]。 */
    fun setDeviceAddress(address: String?) {
        deviceAddress = address
        if (address != null) {
            val code = connectionConfig?.verificationCode ?: "123456"
            val apiKey = connectionConfig?.deepgramApiKey
            connectionConfig = WearableConnectionConfig(
                deviceAddress = address,
                verificationCode = code,
                dumpOverlapWavToDebugDir = dumpOverlapWavToDebugDir,
                deepgramApiKey = apiKey
            )
        }
    }

    /** 运行时开关：是否将 overlap chunk 写入 debug 目录。 */
    fun setDumpOverlapWavToDebugDir(enabled: Boolean) {
        dumpOverlapWavToDebugDir = enabled
    }

    private fun getDeviceAddress(): String? =
        connectionConfig?.deviceAddress ?: deviceAddress

    override fun startRecording(onReady: () -> Unit, onError: (errorMessage: String) -> Unit) {
        debugLog("startRecording() called")
        this.onReady = onReady
        this.onError = onError
        val address = getDeviceAddress()
        if (address.isNullOrBlank()) {
            debugLog("startRecording -> onError: no device address")
            invokeOnError("请先设置设备地址：setConnectionConfig 或 setDeviceAddress")
            return
        }
        if (recording.getAndSet(true)) {
            debugLog("startRecording -> onError: already recording")
            invokeOnError("已在录音中")
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            recording.set(false)
            debugLog("startRecording -> onError: bluetooth off")
            invokeOnError("蓝牙未开启")
            return
        }
        if (!hasBlePermission()) {
            recording.set(false)
            debugLog("startRecording -> onError: no BLE permission")
            invokeOnError("无蓝牙连接权限")
            return
        }
        debugLog("startRecording -> connectGatt address=$address")
        handshakeVerificationCode = connectionConfig?.verificationCode ?: "123456"
        val device = adapter.getRemoteDevice(address)
        connectThenHandshakeThenRecording(device)
    }

    override fun stopRecording() {
        debugLog("stopRecording() called")
        if (!recording.getAndSet(false)) {
            debugLog("stopRecording -> was not recording, no-op")
            return
        }
        debugLog("stopRecording -> disconnecting and releasing")
        step3AwaitingA001Read.set(false)
        stopChunkDelivery()
        stopTranscription()
        reassembler.reset()
        bleStreamReassembler.reset()
        synchronized(bufferLock) { opusFrameBuffer.clear() }
        synchronized(bleStreamLock) {
            opusStreamBuffer.reset()
            opusPendingBuffer.reset()
            pcmMonoBuffer.reset()
            streamOpusDecoder = null
            pcmTimelineBaseWallMs = 0
            pcmChunkDeliveryIndex = 0
        }
        recordingStartTimeMs = 0
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {}
        gatt = null
    }

    override fun isRecording(): Boolean {
        val v = recording.get()
        debugLog("isRecording() -> $v")
        return v
    }

    override fun setListener(listener: AudioChunkProvider.AudioChunkListener?) {
        debugLog("AudioChunkProvider.setListener(${if (listener == null) "null" else "non-null"})")
        audioChunkListener = listener
    }

    override fun startChunkDelivery(chunkDurationMs: Long, overlapDurationMs: Long) {
        debugLog("startChunkDelivery(chunkDurationMs=$chunkDurationMs, overlapDurationMs=$overlapDurationMs)")
        this.chunkDurationMs = chunkDurationMs
        this.overlapDurationMs = overlapDurationMs
        chunkBaseFrameId = null
        if (!chunkDeliveryActive.getAndSet(true)) {
            debugLog("startChunkDelivery -> PCM overlap mode chunk=${chunkDurationMs}ms overlap=${overlapDurationMs}ms stride=${chunkDurationMs - overlapDurationMs}ms")
            // 新路径：由 bleStreamReassembler 每 50 帧触发解码回调，不再跑按 frameId 窗口的 chunkDeliveryLoop
        } else {
            debugLog("startChunkDelivery -> already active, params updated only")
        }
    }

    override fun stopChunkDelivery() {
        debugLog("stopChunkDelivery() called")
        if (chunkDeliveryActive.getAndSet(false)) {
            debugLog("stopChunkDelivery -> buffer mode off")
            chunkBaseFrameId = null
            chunkExecutor?.shutdown()
            chunkExecutor = null
        } else {
            debugLog("stopChunkDelivery -> was not active")
        }
    }

    override fun setListener(listener: TranscriptionProvider.TranscriptionListener?) {
        transcriptionListener = listener
    }

    override fun startTranscription() {
        val apiKey = connectionConfig?.deepgramApiKey?.takeIf { it.isNotBlank() }
        if (apiKey == null) {
            debugLog("startTranscription: no deepgramApiKey in config, skip")
            return
        }
        if (transcriptionActive.getAndSet(true)) {
            debugLog("startTranscription: already active, skip")
            return
        }
        transcriptionStreamStartWallMs = 0
        transcriptionStreamStartWallMsSet = false
        transcriptionPcmBuffer.reset()
        deepgramFluxClient = DeepgramFluxClient(
            apiKey = apiKey,
            sampleRate = WearableBleConfig.SAMPLE_RATE,
            onTurnInfo = { transcript, startSec, endSec ->
                val base = transcriptionStreamStartWallMs
                if (base == 0L) return@DeepgramFluxClient
                val startMs = base + (startSec * 1000).toLong()
                val endMs = base + (endSec * 1000).toLong()
                mainHandler.post {
                    transcriptionListener?.onTranscriptionReady(transcript, startMs, endMs)
                }
            },
            onError = { msg ->
                mainHandler.post { onError?.invoke(msg) }
            },
            onClosed = {
                transcriptionActive.set(false)
                deepgramFluxClient = null
            }
        )
        deepgramFluxClient?.connect()
        debugLog("startTranscription: Deepgram listen-flux connecting")
    }

    override fun stopTranscription() {
        if (!transcriptionActive.getAndSet(false)) return
        deepgramFluxClient?.close()
        deepgramFluxClient = null
        transcriptionStreamStartWallMsSet = false
        transcriptionPcmBuffer.reset()
        debugLog("stopTranscription: closed")
    }

    @SuppressLint("MissingPermission")
    private fun connectThenHandshakeThenRecording(device: BluetoothDevice) {
        a003NotifyCount.set(0)
        a003NotifyBytes.set(0)
        a003FirstLogged = false
        opusFrameCompleteCount.set(0)
        bleStreamReassembler.reset()
        synchronized(bleStreamLock) {
            opusStreamBuffer.reset()
            opusPendingBuffer.reset()
            pcmMonoBuffer.reset()
            streamOpusDecoder = null
            pcmTimelineBaseWallMs = 0
            pcmChunkDeliveryIndex = 0
        }
        debugLog("[STEP1] connectGatt starting address=${device.address}")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, gattCallback)
        }
        if (gatt == null) {
            recording.set(false)
            debugLog("[STEP1] connectGatt returned null -> onError")
            invokeOnError("无法创建 GATT 连接")
        } else {
            debugLog("[STEP1] connectGatt returned OK, wait for onConnectionStateChange")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            debugLog("[STEP1] onConnectionStateChange status=$status newState=$newState (2=CONNECTED 0=DISCONNECTED)")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                recording.set(false)
                debugLog("[STEP1] FAILED connection status!=SUCCESS -> onError")
                invokeOnError("连接失败: status=$status")
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                debugLog("[STEP1] CONNECTED -> discoverServices()")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                debugLog("[STEP1] DISCONNECTED")
                recording.set(false)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            debugLog("[STEP1] onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                recording.set(false)
                debugLog("[STEP1] FAILED service discovery -> onError")
                invokeOnError("服务发现失败: $status")
                return
            }
            val service = gatt.getService(WearableBleConfig.SERVICE_UUID) ?: run {
                recording.set(false)
                debugLog("[STEP1] FAILED service A000 not found -> onError")
                invokeOnError("未找到服务 UUID")
                return
            }
            val transferChar = service.getCharacteristic(WearableBleConfig.TRANSFER_CHAR_UUID)
            if (transferChar == null) {
                recording.set(false)
                debugLog("[STEP1] FAILED A002 not found -> onError")
                invokeOnError("未找到握手特征 A002")
                return
            }
            debugLog("[STEP2] service+A002 OK -> enableTransferNotification (handshake path)")
            enableTransferNotification(gatt, transferChar)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (characteristic.uuid == WearableBleConfig.STATUS_CHAR_UUID &&
                step3AwaitingA001Read.getAndSet(false)
            ) {
                debugLog("[STEP3] onCharacteristicRead A001 status=$status valueLen=${value.size} -> now enable A003 (GATT idle)")
                enableA003NotifyAfterA001(gatt)
            }
        }

        /** API 32 及以下走此回调；value 在 characteristic.value */
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (characteristic.uuid == WearableBleConfig.STATUS_CHAR_UUID &&
                step3AwaitingA001Read.getAndSet(false)
            ) {
                val v = characteristic.value ?: ByteArray(0)
                debugLog("[STEP3] onCharacteristicRead (legacy) A001 status=$status valueLen=${v.size} -> enable A003")
                enableA003NotifyAfterA001(gatt)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val charUuid = descriptor.characteristic?.uuid
            debugLog("[STEP2/3] onDescriptorWrite char=$charUuid status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) return
            if (descriptor.uuid != WearableBleConfig.CCCD_UUID) return
            when {
                charUuid == WearableBleConfig.TRANSFER_CHAR_UUID -> {
                    debugLog("[STEP2] A002 CCCD write SUCCESS -> sendHandshakeRequest")
                    sendHandshakeRequest(gatt)
                }
                charUuid == WearableBleConfig.RECORDING_CHAR_UUID -> {
                    recordingStartTimeMs = System.currentTimeMillis()
                    debugLog("[STEP3] A003 CCCD write SUCCESS -> subscribe OK, recordingStartTimeMs=$recordingStartTimeMs")
                    invokeOnReady()
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                WearableBleConfig.TRANSFER_CHAR_UUID -> handleTransferNotification(gatt, value)
                WearableBleConfig.RECORDING_CHAR_UUID -> {
                    val n = a003NotifyCount.incrementAndGet()
                    a003NotifyBytes.addAndGet(value.size.toLong())
                    if (!a003FirstLogged) {
                        a003FirstLogged = true
                        debugLog("[DATA] A003 first notify len=${value.size} (opus stream entering reassembler)")
                    }
                    if (n == 10 || n == 50 || n % 200 == 0) {
                        debugLog("[DATA] A003 notify count=$n totalBytes=${a003NotifyBytes.get()}")
                    }
                    bleStreamReassembler.feed(value)
                }
                else -> {}
            }
        }
    }

    private fun handleTransferNotification(gatt: BluetoothGatt, value: ByteArray) {
        val unpacked = MessagePacker.unpack(value) ?: run {
            debugLog("[STEP2] A002 notify unpack failed len=${value.size}")
            return
        }
        val (type, data) = unpacked
        debugLog("[STEP2] A002 notify type=$type dataLen=${data.size}")
        when (type) {
            MessageType.CONTROL_RESPONSE -> {
                val statusByte = if (data.size >= 3) data[2].toInt() and 0xFF else -1
                debugLog("[STEP2] CONTROL_RESPONSE statusByte=$statusByte (0=OK)")
                if (data.size >= 3 && statusByte == 0) {
                    debugLog("[STEP2] CONTROL_RESPONSE OK -> triggerDeviceStartAndEnableRecording (STEP3)")
                    triggerDeviceStartAndEnableRecording(gatt)
                }
            }
            MessageType.HANDSHAKE -> {
                if (data.isEmpty()) return
                when (data[0].toInt() and 0xFF) {
                    HandshakeMessageType.HANDSHAKE_RESPONSE.value -> {
                        debugLog("[STEP2] HANDSHAKE_RESPONSE received -> handleHandshakeResponse")
                        handleHandshakeResponse(gatt, data.copyOfRange(1, data.size))
                    }
                    HandshakeMessageType.HANDSHAKE_ERROR.value -> {
                        debugLog("[STEP2] HANDSHAKE_ERROR -> onError")
                        recording.set(false)
                        invokeOnError("设备握手错误")
                    }
                    else -> debugLog("[STEP2] HANDSHAKE other inner type=${data[0].toInt() and 0xFF}")
                }
            }
            else -> {}
        }
    }

    private fun handleHandshakeResponse(gatt: BluetoothGatt, payload: ByteArray) {
        if (payload.size != 6) {
            debugLog("[STEP2] handleHandshakeResponse bad len=${payload.size} expected 6")
            return
        }
        val responseCode = payload.map { it.toInt().toChar() }.joinToString("")
        val sent = handshakeVerificationCode
        if (sent == null || responseCode != sent) {
            debugLog("[STEP2] handshake mismatch sent=$sent recv=$responseCode -> onError")
            recording.set(false)
            invokeOnError("握手校验码不匹配")
            return
        }
        debugLog("[STEP2] handshake verification OK -> sendHandshakeCompleted + setTime(300ms)")
        sendHandshakeCompleted(gatt)
        mainHandler.postDelayed({ sendSetTime(gatt) }, 300)
    }

    @SuppressLint("MissingPermission")
    private fun enableTransferNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        debugLog("[STEP2] enableTransferNotification A002 setNotification+write CCCD")
        gatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(WearableBleConfig.CCCD_UUID)
        if (cccd == null) {
            recording.set(false)
            debugLog("[STEP2] FAILED A002 no CCCD")
            invokeOnError("A002 未找到 CCCD")
            return
        }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(cccd)
    }

    private fun sendHandshakeRequest(gatt: BluetoothGatt) {
        val code = handshakeVerificationCode ?: "123456"
        val deviceCode = getOrCreateDeviceUUID(context)
        val data = encodeHandshakeRequest(code, deviceCode, 0)
        val packet = MessagePacker.pack(MessageType.HANDSHAKE, data)
        val ok = writeToTransferChar(gatt, packet)
        debugLog("[STEP2] sendHandshakeRequest writeToTransferChar ok=$ok packetLen=${packet.size}")
    }

    private fun sendHandshakeCompleted(gatt: BluetoothGatt) {
        val data = byteArrayOf(HandshakeMessageType.HANDSHAKE_COMPLETED.value.toByte())
        val packet = MessagePacker.pack(MessageType.HANDSHAKE, data)
        val ok = writeToTransferChar(gatt, packet)
        debugLog("[STEP2] sendHandshakeCompleted write ok=$ok")
    }

    private fun sendSetTime(gatt: BluetoothGatt?) {
        if (gatt == null) return
        nextCommandId = (nextCommandId + 1) and 0xFFFF
        val requestId = nextCommandId
        val payload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(System.currentTimeMillis()).array()
        val data = ByteBuffer.allocate(3 + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(requestId.toShort())
            put(ControlCommand.SET_TIME.value.toByte())
            put(payload)
        }.array()
        val packet = MessagePacker.pack(MessageType.CONTROL_REQUEST, data)
        val ok = writeToTransferChar(gatt, packet)
        debugLog("[STEP2] sendSetTime requestId=$requestId write ok=$ok")
    }

    /**
     * STEP3 入口：先 read A001（若存在），在 [onCharacteristicRead] 回调里再写 A003 CCCD，
     * 避免与未完成的 read 并发导致 writeDescriptor 返回 false。
     * 若无 A001 或 read 未入队，则延迟到主线程下一拍再写 A003。
     */
    @SuppressLint("MissingPermission")
    private fun triggerDeviceStartAndEnableRecording(gatt: BluetoothGatt?) {
        if (gatt == null) return
        debugLog("[STEP3] triggerDeviceStart: read A001 first, then enable A003 after read completes")
        val service = gatt.getService(WearableBleConfig.SERVICE_UUID) ?: run {
            recording.set(false)
            invokeOnError("服务丢失")
            return
        }
        val statusChar = service.getCharacteristic(WearableBleConfig.STATUS_CHAR_UUID)
        if (statusChar != null) {
            step3AwaitingA001Read.set(true)
            val r = gatt.readCharacteristic(statusChar)
            debugLog("[STEP3] readCharacteristic A001 queued ok=$r")
            if (!r) {
                step3AwaitingA001Read.set(false)
                debugLog("[STEP3] readCharacteristic A001 failed to queue -> post enable A003")
                mainHandler.post { enableA003NotifyAfterA001(gatt) }
            } else {
                // 若设备未回调 onCharacteristicRead，超时后仍尝试开 A003，避免永远卡住
                mainHandler.postDelayed({
                    if (step3AwaitingA001Read.compareAndSet(true, false) && recording.get()) {
                        debugLog("[STEP3] A001 read timeout -> enable A003 anyway")
                        enableA003NotifyAfterA001(gatt)
                    }
                }, 2000)
            }
            return
        }
        debugLog("[STEP3] no A001 -> enable A003 immediately")
        enableA003NotifyAfterA001(gatt)
    }

    /**
     * 在 A001 read 完成（或无需 read）后调用：setNotification + writeDescriptor A003 CCCD。
     */
    @SuppressLint("MissingPermission")
    private fun enableA003NotifyAfterA001(gatt: BluetoothGatt) {
        val service = gatt.getService(WearableBleConfig.SERVICE_UUID) ?: run {
            recording.set(false)
            invokeOnError("服务丢失")
            return
        }
        val recordingChar = service.getCharacteristic(WearableBleConfig.RECORDING_CHAR_UUID)
        if (recordingChar == null) {
            recording.set(false)
            debugLog("[STEP3] FAILED A003 char not found")
            invokeOnError("未找到录音特征 A003")
            return
        }
        gatt.setCharacteristicNotification(recordingChar, true)
        val cccd = recordingChar.getDescriptor(WearableBleConfig.CCCD_UUID)
        if (cccd == null) {
            recording.set(false)
            debugLog("[STEP3] FAILED A003 CCCD not found")
            invokeOnError("A003 未找到 CCCD")
            return
        }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val w = gatt.writeDescriptor(cccd)
        debugLog("[STEP3] writeDescriptor A003 CCCD ok=$w (true=queued, wait onDescriptorWrite for STEP3 complete)")
        if (!w) {
            debugLog("[STEP3] writeDescriptor still false -> retry after 150ms")
            mainHandler.postDelayed({
                if (!recording.get()) return@postDelayed
                val w2 = gatt.writeDescriptor(cccd)
                debugLog("[STEP3] writeDescriptor A003 CCCD retry ok=$w2")
                if (!w2) {
                    recording.set(false)
                    invokeOnError("A003 开启通知失败(writeDescriptor)")
                }
            }, 150)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeToTransferChar(gatt: BluetoothGatt?, value: ByteArray): Boolean {
        if (gatt == null) return false
        if (!hasBlePermission()) return false
        val service = gatt.getService(WearableBleConfig.SERVICE_UUID) ?: return false
        val transferChar = service.getCharacteristic(WearableBleConfig.TRANSFER_CHAR_UUID) ?: return false
        transferChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        transferChar.value = value
        return gatt.writeCharacteristic(transferChar)
    }

    private fun encodeHandshakeRequest(verificationCode: String, deviceCode: String, accountCode: Int): ByteArray {
        val message = ByteArray(27)
        message[0] = HandshakeMessageType.HANDSHAKE_REQUEST.value.toByte()
        verificationCode.padEnd(6, '\u0000').toByteArray(Charsets.UTF_8).copyInto(message, 1)
        uuidToBytes(deviceCode).copyInto(message, 7)
        message[23] = (accountCode and 0xFF).toByte()
        message[24] = ((accountCode shr 8) and 0xFF).toByte()
        message[25] = ((accountCode shr 16) and 0xFF).toByte()
        message[26] = ((accountCode shr 24) and 0xFF).toByte()
        return message
    }

    private fun uuidToBytes(uuid: String): ByteArray =
        uuid.replace("-", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun getOrCreateDeviceUUID(context: Context): String {
        val sp = context.getSharedPreferences("wearable_handshake_prefs", Context.MODE_PRIVATE)
        return sp.getString("device_uuid", null) ?: run {
            val newId = UUID.randomUUID().toString()
            sp.edit().putString("device_uuid", newId).apply()
            newId
        }
    }

    /**
     * BleAudioReassembler.writeFrameToFile 等价 append → 实时 80 字节一包解码 → mono PCM；
     * PCM 满 chunkDurationMs 封 WAV，再按 stride 前移，保留 overlapDurationMs 重叠。
     */
    private fun onBleStreamFrameAppended(concatenated: ByteArray) {
        val cnt = opusFrameCompleteCount.incrementAndGet()
        synchronized(bleStreamLock) {
            try {
                opusStreamBuffer.write(concatenated)
            } catch (_: Exception) {}
            opusPendingBuffer.write(concatenated)
            if (cnt <= 3 || cnt % 100 == 0) {
                debugLog("[DATA] bleStream append#$cnt opusLen=${concatenated.size} pcmBytes=${pcmMonoBuffer.size()} pendingOpus=${opusPendingBuffer.size()}")
            }
            drainOpusPendingToPcm()
            if (chunkDeliveryActive.get()) {
                emitOverlappingWavChunks()
            }
        }
    }

    /** 从 opusPendingBuffer 按 80 字节一包解码，追加 mono PCM 到 pcmMonoBuffer；若转写开启则 tee 到 transcriptionPcmBuffer 并发送 Deepgram。 */
    private fun drainOpusPendingToPcm() {
        val frameSize = WearableBleConfig.OPUS_FRAME_SIZE_BYTES
        val pending = opusPendingBuffer.toByteArray()
        if (pending.size < frameSize) return
        var dec = streamOpusDecoder
        if (dec == null) {
            dec = OpusDecoder(WearableBleConfig.SAMPLE_RATE, WearableBleConfig.OPUS_CHANNELS)
            streamOpusDecoder = dec
        }
        val pcmStereo = ShortArray(320 * WearableBleConfig.OPUS_CHANNELS)
        val frameBuf = ByteArray(frameSize)
        val teeToTranscription = transcriptionActive.get()
        var offset = 0
        val total = pending.size
        while (offset + frameSize <= total) {
            System.arraycopy(pending, offset, frameBuf, 0, frameSize)
            offset += frameSize
            val decoded = dec.decode(
                frameBuf, 0, frameSize,
                pcmStereo, 0, 320,
                false
            )
            if (decoded <= 0) continue
            // decoded = 每声道样本数；mono 样本数 = decoded，字节 = decoded * 2（不可写成 decoded*2 个 short 再只写一半，否则会 append 大量 0 → 断续、时长错乱）
            val samples = decoded.coerceAtMost(320)
            val bb = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until samples) {
                val idx = i * WearableBleConfig.OPUS_CHANNELS
                if (idx + 1 >= pcmStereo.size) break
                val mono = ((pcmStereo[idx].toInt() + pcmStereo[idx + 1].toInt()) / 2).toShort()
                bb.putShort(mono)
            }
            pcmMonoBuffer.write(bb.array(), 0, bb.position())
            if (teeToTranscription) transcriptionPcmBuffer.write(bb.array(), 0, bb.position())
        }
        opusPendingBuffer.reset()
        if (offset < total) {
            opusPendingBuffer.write(pending, offset, total - offset)
        }
        // 转写：本轮 tee 的 PCM 发送给 Deepgram，并维护流起始墙钟
        if (teeToTranscription && transcriptionPcmBuffer.size() > 0) {
            val pcmBytes = transcriptionPcmBuffer.toByteArray()
            transcriptionPcmBuffer.reset()
            if (pcmTimelineBaseWallMs == 0L && pcmMonoBuffer.size() > 0) {
                pcmTimelineBaseWallMs = System.currentTimeMillis()
            }
            if (!transcriptionStreamStartWallMsSet && pcmTimelineBaseWallMs != 0L) {
                val bytesBeforeThisBatch = (pcmMonoBuffer.size() - pcmBytes.size).coerceAtLeast(0)
                transcriptionStreamStartWallMs = pcmTimelineBaseWallMs + bytesBeforeThisBatch * 1000L / (WearableBleConfig.SAMPLE_RATE * 2)
                transcriptionStreamStartWallMsSet = true
            }
            deepgramFluxClient?.sendPcm(pcmBytes)
        }
    }

    /**
     * PCM 时间轴上窗口 [base, base+chunkDurationMs)，交付后 base += stride，重叠段保留。
     */
    private fun emitOverlappingWavChunks() {
        val sampleRate = WearableBleConfig.SAMPLE_RATE
        val chunkMs = chunkDurationMs
        val overlapMs = overlapDurationMs
        if (chunkMs <= 0) return
        val strideMs = (chunkMs - overlapMs).coerceAtLeast(0)
        // mono 16-bit: bytes per ms = sampleRate * 2 / 1000
        val chunkBytes = (sampleRate * 2L * chunkMs / 1000L).toInt()
        val strideBytes = (sampleRate * 2L * strideMs / 1000L).toInt()
        if (chunkBytes <= 0) return
        val pcm = pcmMonoBuffer.toByteArray()
        if (pcmTimelineBaseWallMs == 0L && pcm.isNotEmpty()) {
            pcmTimelineBaseWallMs = System.currentTimeMillis()
        }
        var baseWall = pcmTimelineBaseWallMs
        var offset = 0
        while (offset + chunkBytes <= pcm.size) {
            val chunkPcm = pcm.copyOfRange(offset, offset + chunkBytes)
            val wav = buildWavWithHeader(chunkPcm, sampleRate, 1)
            val startMs = baseWall
            val endMs = baseWall + chunkMs
            pcmChunkDeliveryIndex++
            debugLog("[CHUNK] overlap DELIVERED #$pcmChunkDeliveryIndex wavBytes=${wav.size} wall=$startMs..$endMs dumpFile=$dumpOverlapWavToDebugDir")
            if (dumpOverlapWavToDebugDir) {
                saveWavChunkToDebugDir(wav, pcmChunkDeliveryIndex, startMs, endMs)
            }
            audioChunkListener?.onAudioChunkReady(AudioChunk(wav, startMs, endMs))
            if (strideBytes <= 0) break
            offset += strideBytes
            baseWall += strideMs
        }
        if (offset > 0) {
            val remaining = pcm.size - offset
            pcmMonoBuffer.reset()
            if (remaining > 0) {
                pcmMonoBuffer.write(pcm, offset, remaining)
            }
            pcmTimelineBaseWallMs = baseWall
        }
    }

    private fun saveWavChunkToDebugDir(wav: ByteArray, index: Int, startMs: Long, endMs: Long) {
        try {
            val dir = context.getExternalFilesDir("wearable_wav_debug")
                ?: File(context.filesDir, "wearable_wav_debug").apply { mkdirs() }
            if (!dir.exists()) dir.mkdirs()
            val name = "overlap_%03d_%d_%d.wav".format(index, startMs, endMs)
            FileOutputStream(File(dir, name)).use { it.write(wav) }
        } catch (e: Exception) {
            debugLog("[CHUNK] saveWavChunk FAIL: ${e.message}")
        }
    }

    private fun onCompleteOpusFrame(frameId: Int, opusFrame: ByteArray) {
        val cnt = opusFrameCompleteCount.incrementAndGet()
        synchronized(bufferLock) {
            if (opusFrameBuffer.isEmpty()) {
                recordingStartTimeMs = System.currentTimeMillis()
            }
            opusFrameBuffer[frameId] = opusFrame
            val bufSize = opusFrameBuffer.size
            if (cnt <= 3 || cnt % 100 == 0) {
                debugLog("[DATA] onCompleteOpusFrame frameId=$frameId opusLen=${opusFrame.size} bufferFrames=$bufSize totalComplete=$cnt")
            }
        }
    }

    private fun chunkDeliveryLoop() {
        val frameDurationMs = WearableBleConfig.OPUS_FRAME_DURATION_MS
        val chunkFrameCount = (chunkDurationMs / frameDurationMs).toInt()
        val strideFrames = ((chunkDurationMs - overlapDurationMs) / frameDurationMs).toInt().coerceAtLeast(1)
        val overlapFrames = (overlapDurationMs / frameDurationMs).toInt().coerceAtLeast(0)
        /** null = 首次从 buffer.min 起算；非 null = 下一窗从该 frameId 开始 */
        var nextStartFrameId: Int? = null
        var loopIteration = 0
        debugLog("[CHUNK] loop start chunkFrames=$chunkFrameCount overlapFrames=$overlapFrames strideFrames=$strideFrames")
        while (chunkDeliveryActive.get()) {
            loopIteration++
            try {
                val result = tryBuildNextChunkAtStartFrame(nextStartFrameId)
                // 仅满窗时 result.wav 非 null；partial 已在 tryBuild 内 return null，此处只处理满窗
                if (result.wav != null) {
                    debugLog("[CHUNK] DELIVERED wavBytes=${result.wav.size} wallStart=${result.startWallMs} wallEnd=${result.endWallMs} startFrameId=${result.startFrameId}")
                    audioChunkListener?.onAudioChunkReady(
                        AudioChunk(result.wav, result.startWallMs, result.endWallMs)
                    )
                    nextStartFrameId = result.startFrameId + strideFrames
                    trimBufferBeforeFrameId(nextStartFrameId - overlapFrames)
                } else if (loopIteration <= 5 || loopIteration % 15 == 0) {
                    synchronized(bufferLock) {
                        val minId = opusFrameBuffer.keys.minOrNull()
                        val maxId = opusFrameBuffer.keys.maxOrNull()
                        debugLog("[CHUNK] tryBuild null iter=$loopIteration nextStartFrameId=$nextStartFrameId bufferSize=${opusFrameBuffer.size} range=$minId..$maxId")
                    }
                }
            } catch (e: Exception) {
                debugLog("[CHUNK] loop error ${e.message}")
                Log.e(TAG, "chunkDeliveryLoop error", e)
            }
            Thread.sleep(2000)
        }
        debugLog("[CHUNK] loop exit")
    }

    /**
     * 仅删除严格早于 cutoff 的帧；用于 full chunk 后释放内存，保留 overlap 段。
     */
    private fun trimBufferBeforeFrameId(cutoffFrameId: Int) {
        synchronized(bufferLock) {
            val toRemove = opusFrameBuffer.keys.filter { it < cutoffFrameId }
            if (toRemove.isNotEmpty()) {
                debugLog("[CHUNK] trim remove ${toRemove.size} frames with id < $cutoffFrameId")
            }
            toRemove.forEach { opusFrameBuffer.remove(it) }
        }
    }

    private data class ChunkBuildResult(
        val wav: ByteArray?,
        val startWallMs: Long,
        val endWallMs: Long,
        val startFrameId: Int,
        val framesDecoded: Int,
        val wasFullChunk: Boolean
    )

    /**
     * @param startFrameId 下一窗起点；null 表示首次，用 buffer 当前 min 作为起点。
     */
    private fun tryBuildNextChunkAtStartFrame(startFrameId: Int?): ChunkBuildResult {
        val frameDurationMs = WearableBleConfig.OPUS_FRAME_DURATION_MS
        val chunkFrameCount = (chunkDurationMs / frameDurationMs).toInt()

        val frames: List<ByteArray>
        val resolvedStart: Int
        val endFrameId: Int
        synchronized(bufferLock) {
            if (opusFrameBuffer.isEmpty()) {
                debugLog("[CHUNK] tryBuild -> buffer EMPTY")
                return ChunkBuildResult(null, 0, 0, 0, 0, false)
            }
            if (chunkBaseFrameId == null) {
                chunkBaseFrameId = opusFrameBuffer.keys.minOrNull()
                debugLog("[CHUNK] chunkBaseFrameId=${chunkBaseFrameId}")
            }
            val base = chunkBaseFrameId!!
            resolvedStart = startFrameId ?: base
            endFrameId = resolvedStart + chunkFrameCount
            val expectedFrameCount = endFrameId - resolvedStart

            frames = (resolvedStart until endFrameId)
                .mapNotNull { opusFrameBuffer[it]?.copyOf() }
            if (frames.isEmpty()) {
                val minId = opusFrameBuffer.keys.minOrNull()
                val maxId = opusFrameBuffer.keys.maxOrNull()
                debugLog("[CHUNK] tryBuild startFrameId=$resolvedStart end=$endFrameId buffer=$minId..$maxId -> no frames")
                return ChunkBuildResult(null, 0, 0, resolvedStart, 0, false)
            }
            if (frames.size < expectedFrameCount) {
                // 未满窗不解码、不回调；下一循环仍从同一 resolvedStart 重试，直到凑满
                debugLog("[CHUNK] tryBuild partial ${frames.size}/$expectedFrameCount [$resolvedStart,$endFrameId) -> wait full window")
                return ChunkBuildResult(null, 0, 0, resolvedStart, frames.size, false)
            }
        }

        val base = chunkBaseFrameId!!
        val startWallMs = recordingStartTimeMs + (resolvedStart - base) * frameDurationMs

        val pcmMono = decodeOpusFramesToMonoPcm(frames)
        if (pcmMono.isEmpty()) {
            debugLog("[CHUNK] tryBuild decode pcm EMPTY frames=${frames.size}")
            return ChunkBuildResult(null, startWallMs, startWallMs, resolvedStart, frames.size, false)
        }
        val actualDurationMs = (pcmMono.size / 2) * 1000L / WearableBleConfig.SAMPLE_RATE
        val wav = buildWavWithHeader(pcmMono, WearableBleConfig.SAMPLE_RATE, 1)
        // 能走到这里说明 frames.size == chunkFrameCount（满窗），否则已在上面 return
        val wasFull = true
        debugLog("[CHUNK] tryBuild OK start=$resolvedStart frames=${frames.size} full=true wav=${wav.size}")
        return ChunkBuildResult(
            wav,
            startWallMs,
            startWallMs + actualDurationMs,
            resolvedStart,
            frames.size,
            wasFull
        )
    }

    private fun decodeOpusFramesToMonoPcm(frames: List<ByteArray>): ByteArray {
        if (frames.isEmpty()) return ByteArray(0)
        val decoder = OpusDecoder(WearableBleConfig.SAMPLE_RATE, WearableBleConfig.OPUS_CHANNELS)
        val frameSizeBytes = WearableBleConfig.OPUS_FRAME_SIZE_BYTES
        val samplesPerFramePerChannel = 320
        val pcmStereo = ShortArray(samplesPerFramePerChannel * WearableBleConfig.OPUS_CHANNELS)
        val out = mutableListOf<Byte>()
        for (frame in frames) {
            if (frame.size < frameSizeBytes) continue
            val decoded = decoder.decode(
                frame, 0, frameSizeBytes.coerceAtMost(frame.size),
                pcmStereo, 0, samplesPerFramePerChannel,
                false
            )
            if (decoded <= 0) continue
            val samplesToWrite = (decoded * WearableBleConfig.OPUS_CHANNELS).coerceAtMost(pcmStereo.size)
            val bb = ByteBuffer.allocate(samplesToWrite * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until samplesToWrite step 2) {
                val mono = ((pcmStereo[i].toInt() + pcmStereo[i + 1].toInt()) / 2).toShort()
                bb.putShort(mono)
            }
            out.addAll(bb.array().toList())
        }
        return out.toByteArray()
    }

    private fun buildWavWithHeader(pcmBytes: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val dataSize = pcmBytes.size
        val fileSize = 36 + dataSize
        val byteRate = sampleRate * channels * 2
        val blockAlign = (channels * 2).toShort()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(fileSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(dataSize)
        return header.array() + pcmBytes
    }

    private fun hasBlePermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
        return ActivityCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun invokeOnReady() {
        debugLog("onReady callback (A003 subscribed, capture started)")
        try {
            onReady?.invoke()
        } catch (_: Exception) {}
    }

    private fun invokeOnError(msg: String) {
        debugLog("onError callback: $msg")
        try {
            onError?.invoke(msg)
        } catch (_: Exception) {}
    }

    /**
     * 与 [com.example.nunarecorder.ble.BleAudioReassembler] 同结构：feed → parseAudioPayload → 拼齐后
     * 调用 [onAppendFrame]（等价 writeFrameToFile 写入内容），不经过按 frameId 的 map buffer。
     */
    private class BleStreamOpusReassembler(
        private val onAppendFrame: (ByteArray) -> Unit
    ) {
        private val frames = mutableMapOf<Int, BleFrameInfo>()

        private data class BleFrameInfo(
            var frameSize: Int,
            var totalChunks: Int,
            var timestamp: Long,
            val chunks: MutableMap<Int, ByteArray>
        )

        fun reset() {
            frames.clear()
        }

        fun feed(data: ByteArray) {
            var idx = 0
            val totalLen = data.size
            val minHeader = WearableBleConfig.MIN_HEADER_LEN
            val minBiz = WearableBleConfig.MIN_AUDIO_BIZ_HEADER
            while (idx + minHeader <= totalLen) {
                if ((data[idx].toInt() and 0xFF) != WearableBleConfig.HEADER_MAGIC) {
                    idx++
                    continue
                }
                val msgType = data[idx + 1].toInt() and 0xFF
                val length = readLeU16(data, idx + 2)
                val msgTotalLen = minHeader + length
                if (idx + msgTotalLen > totalLen) break
                val payload = data.copyOfRange(idx + minHeader, idx + msgTotalLen)
                if (msgType == WearableBleConfig.AUDIO_TYPE && payload.size >= minBiz) {
                    parseAudioPayload(payload)
                }
                idx += msgTotalLen
            }
        }

        private fun parseAudioPayload(payload: ByteArray) {
            val frameId = readLeU16(payload, 0)
            val frameSize = readLeU16(payload, 2)
            val chunkId = payload[4].toInt() and 0xFF
            val totalChunks = payload[5].toInt() and 0xFF
            val timestamp = readLeU64(payload, 6)
            val opusData = payload.copyOfRange(14, payload.size)
            val frame = frames.getOrPut(frameId) {
                BleFrameInfo(frameSize, totalChunks, timestamp, mutableMapOf())
            }
            frame.frameSize = frameSize
            frame.totalChunks = maxOf(frame.totalChunks, totalChunks)
            frame.timestamp = timestamp
            frame.chunks[chunkId] = opusData
            if (frame.chunks.size == frame.totalChunks) {
                if (appendFrame(frame)) frames.remove(frameId)
            }
        }

        /** @return true 已追加并应从 map 移除 */
        private fun appendFrame(frame: BleFrameInfo): Boolean {
            val concatenated = ByteArray(frame.chunks.values.sumOf { it.size })
            var pos = 0
            for (cid in 0 until frame.totalChunks) {
                val chunk = frame.chunks[cid] ?: return false
                System.arraycopy(chunk, 0, concatenated, pos, chunk.size)
                pos += chunk.size
            }
            if (pos != concatenated.size) return false
            onAppendFrame(concatenated)
            return true
        }

        private fun readLeU16(b: ByteArray, offset: Int): Int {
            return (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)
        }

        private fun readLeU64(b: ByteArray, offset: Int): Long {
            var v = 0L
            for (i in 0 until 8) {
                v = v or (((b[offset + i].toInt() and 0xFF).toLong()) shl (8 * i))
            }
            return v
        }
    }

    /**
     * 旧按 frameId 窗口重组（已不作为 A003 主路径）。
     */
    private class OpusFrameReassembler(private val onFrame: (frameId: Int, opusFrame: ByteArray) -> Unit) {
        private val frames = mutableMapOf<Int, FrameInfo>()

        fun reset() {
            frames.clear()
        }

        fun feed(data: ByteArray) {
            var idx = 0
            val totalLen = data.size
            while (idx + WearableBleConfig.MIN_HEADER_LEN <= totalLen) {
                if ((data[idx].toInt() and 0xFF) != WearableBleConfig.HEADER_MAGIC) {
                    idx++
                    continue
                }
                val msgType = data[idx + 1].toInt() and 0xFF
                val length = readLeU16(data, idx + 2)
                val msgTotalLen = WearableBleConfig.MIN_HEADER_LEN + length
                if (idx + msgTotalLen > totalLen) break
                val payload = data.copyOfRange(idx + WearableBleConfig.MIN_HEADER_LEN, idx + msgTotalLen)
                if (msgType == WearableBleConfig.AUDIO_TYPE && payload.size >= WearableBleConfig.MIN_AUDIO_BIZ_HEADER) {
                    val frameId = readLeU16(payload, 0)
                    val frameSize = readLeU16(payload, 2)
                    val chunkId = payload[4].toInt() and 0xFF
                    val totalChunks = payload[5].toInt() and 0xFF
                    val opusData = payload.copyOfRange(14, payload.size)
                    val frame = frames.getOrPut(frameId) {
                        FrameInfo(frameSize, totalChunks, mutableMapOf())
                    }
                    frame.frameSize = frameSize
                    frame.chunks[chunkId] = opusData
                    frame.totalChunks = maxOf(frame.totalChunks, totalChunks)
                    if (frame.chunks.size == frame.totalChunks) {
                        val concatenated = ByteArray(frame.chunks.values.sumOf { it.size })
                        var pos = 0
                        var complete = true
                        for (c in 0 until frame.totalChunks) {
                            val ch = frame.chunks[c]
                            if (ch == null) {
                                complete = false
                                break
                            }
                            System.arraycopy(ch, 0, concatenated, pos, ch.size)
                            pos += ch.size
                        }
                        if (complete && pos == concatenated.size) {
                            onFrame(frameId, concatenated)
                            frames.remove(frameId)
                        } else if (!complete) {
                            // 与 BleAudioReassembler.writeFrameToFile 一致：缺 chunk 不写、不 remove，保留等后续包
                            Log.w(TAG, "[reassembler] frameId=$frameId incomplete chunks 0..${frame.totalChunks - 1}, keep")
                        }
                        // pos != size：拼接长度不一致，不 remove，避免丢帧（原逻辑会 remove 导致 buffer 空）
                    }
                }
                idx += msgTotalLen
            }
        }

        private data class FrameInfo(
            var frameSize: Int,
            var totalChunks: Int,
            val chunks: MutableMap<Int, ByteArray>
        )

        private fun readLeU16(b: ByteArray, offset: Int): Int {
            return (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)
        }
    }
}

