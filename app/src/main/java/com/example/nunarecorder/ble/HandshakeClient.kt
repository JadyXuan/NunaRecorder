package com.example.nunarecorder.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat

sealed interface HandshakeEvent {
    data object Started : HandshakeEvent
    data object VerificationAccepted : HandshakeEvent
    data object Ready : HandshakeEvent
    data class Failed(val reason: String) : HandshakeEvent
}

class HandshakeClient(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onEvent: (HandshakeEvent) -> Unit = {}
) {

    companion object {
        private const val TAG = "HandshakeClient"
    }

    private var verificationCodeSent: String? = null
    private var nextCommandId: Int = 0x2d01
    private var pendingSetTimeCommandId: Int? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var handshakeGeneration = 0
    private var timeoutRunnable: Runnable? = null

    private fun nextCommandId(): Int {
        nextCommandId = (nextCommandId + 1) and 0xFFFF
        return nextCommandId
    }

    fun startHandshake(gatt: BluetoothGatt) {
        handshakeGeneration++
        val generation = handshakeGeneration
        cancelTimeout()
        verificationCodeSent = null
        pendingSetTimeCommandId = null
        onEvent(HandshakeEvent.Started)
        log("HS: startHandshake() called")

        val verificationCode = "123456"
        verificationCodeSent = verificationCode
        log("HS: generated verificationCode='$verificationCode'")

        val deviceCode = getOrCreateDeviceUUID(context)
        log("HS: deviceCode (UUID) = $deviceCode")

        val data = encodeHandshakeRequest(
            verificationCode = verificationCode,
            deviceCode = deviceCode,
            accountCode = 0
        )

        log("HS: handshakeRequest data=${data.toHex()}")

        val packet = MessagePacker.pack(
            type = MessageType.HANDSHAKE,
            data = data
        )

        log("HS: handshakeRequest packet=${packet.toHex()}")

        if (!writeToTransferChar(gatt, packet)) {
            fail("无法发送握手请求")
            return
        }
        timeoutRunnable = Runnable {
            if (generation == handshakeGeneration && verificationCodeSent != null) {
                fail("等待设备响应超时")
            }
        }.also { handler.postDelayed(it, 12_000L) }
    }

    fun onNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        val value = characteristic.value ?: return

        // 只看 TRANSFER_CHAR 的通知
        if (characteristic.uuid.toString().equals(ProtoConfig.Service.TRANSFER_CHAR_UUID, ignoreCase = true)) {
            log("HS: onNotification from TRANSFER, raw=${value.toHex()}")

            val unpacked = MessagePacker.unpack(value)
            if (unpacked == null) {
                log("HS: unpack failed, not protocol format")
                return
            }

            val (type, data) = unpacked
            log("HS: unpack ok, type=$type, data=${data.toHex()}")

            // 处理 CONTROL_RESPONSE（包含 SET_TIME_RESPONSE）
            if (type == MessageType.CONTROL_RESPONSE) {
                log("HS: received CONTROL_RESPONSE")
                handleControlResponse(gatt, data)
                return
            }

            if (type != MessageType.HANDSHAKE) {
                log("HS: type is not HANDSHAKE, ignore in HS client")
                return
            }

            if (data.isEmpty()) {
                log("HS: empty data for HANDSHAKE")
                return
            }

            val msgTypeValue = data[0].toInt() and 0xFF
            log("HS: inner handshake msgType=0x${msgTypeValue.toString(16)}")

            when (msgTypeValue) {
                HandshakeMessageType.HANDSHAKE_RESPONSE.value -> {
                    val payload = data.copyOfRange(1, data.size)
                    log("HS: HANDSHAKE_RESPONSE payload=${payload.toHex()}")
                    handleHandshakeResponse(gatt, payload)
                }
                HandshakeMessageType.HANDSHAKE_ERROR.value -> {
                    log("HS: HANDSHAKE_ERROR received, rawData=${data.toHex()}")
                    fail("设备返回握手错误")
                }
                else -> {
                    log("HS: unknown handshake inner type=0x${msgTypeValue.toString(16)}, data=${data.toHex()}")
                }
            }
        }
    }

    // 处理控制响应
    private fun handleControlResponse(gatt: BluetoothGatt, data: ByteArray) {
        log("HS: handleControlResponse, raw data=${data.toHex()}, len=${data.size}")

        if (data.size < 3) {
            log("HS: CONTROL_RESPONSE too short, len=${data.size}")
            fail("设备控制响应格式错误")
            return
        }

        val requestId = ((data[1].toInt() and 0xFF) shl 8) or (data[0].toInt() and 0xFF)
        val statusCode = if (data.size > 2) data[2].toInt() and 0xFF else -1

        log("HS: CONTROL_RESPONSE: requestId=0x${requestId.toString(16)} ($requestId), statusCode=$statusCode")

        val pendingRequestId = pendingSetTimeCommandId
        if (pendingRequestId == null || requestId != pendingRequestId) {
            log("HS: ignore unrelated CONTROL_RESPONSE, pending=$pendingRequestId")
            return
        }

        if (statusCode == 0) {
            log("HS: ✅ Command success (requestId=$requestId), trigger device start...")
            pendingSetTimeCommandId = null
            if (triggerDeviceStart(gatt)) {
                verificationCodeSent = null
                cancelTimeout()
                onEvent(HandshakeEvent.Ready)
            } else {
                fail("无法触发设备启动")
            }
        } else {
            log("HS: ❌ Command failed with status: $statusCode")
            pendingSetTimeCommandId = null
            fail("设备时间同步失败 (status=$statusCode)")
        }
    }

    private fun handleHandshakeResponse(
        gatt: BluetoothGatt,
        payload: ByteArray
    ) {
        log("HS: handleHandshakeResponse, payload=${payload.toHex()} (len=${payload.size})")

        if (payload.size != 6) {
            log("HS: invalid response length, expect 6")
            fail("握手响应长度错误")
            return
        }

        val responseCode = payload.map { it.toInt().toChar() }.joinToString("")
        log("HS: responseCode='$responseCode'")

        val sent = verificationCodeSent
        if (sent == null) {
            log("HS: no verificationCodeSent, handshake not started yet?")
            fail("收到无效的握手响应")
            return
        }

        if (responseCode != sent) {
            log("HS: verificationCode mismatch, sent='$sent', recv='$responseCode'")
            fail("验证码不匹配")
            return
        }

        log("HS: verificationCode matched, now send COMPLETED + SET_TIME")
        onEvent(HandshakeEvent.VerificationAccepted)

        if (!sendHandshakeCompleted(gatt)) {
            fail("无法发送握手完成消息")
            return
        }

        // 延迟 300ms 再发送 SET_TIME
        val generation = handshakeGeneration
        handler.postDelayed({
            if (generation == handshakeGeneration && verificationCodeSent != null) {
                sendSetTime(gatt, System.currentTimeMillis())
            }
        }, 300)
    }

    private fun sendHandshakeCompleted(gatt: BluetoothGatt): Boolean {
        val data = ByteArray(1)
        data[0] = HandshakeMessageType.HANDSHAKE_COMPLETED.value.toByte()

        log("HS: handshakeCompleted data=${data.toHex()}")

        val packet = MessagePacker.pack(
            type = MessageType.HANDSHAKE,
            data = data
        )

        log("HS: handshakeCompleted packet=${packet.toHex()}")

        return writeToTransferChar(gatt, packet)
    }

    private fun sendSetTime(gatt: BluetoothGatt, timestamp: Long) {
        val requestId = nextCommandId()
        pendingSetTimeCommandId = requestId
        val payload = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(timestamp)
            .array()

        val data = ByteBuffer.allocate(3 + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putShort(requestId.toShort())
                put(ControlCommand.SET_TIME.value.toByte())
                put(payload)
            }
            .array()

        log("HS: setTime(requestId=$requestId, ts=$timestamp) data=${data.toHex()}")

        val packet = MessagePacker.pack(
            type = MessageType.CONTROL_REQUEST,
            data = data
        )

        log("HS: setTime packet=${packet.toHex()}")

        if (!writeToTransferChar(gatt, packet)) {
            pendingSetTimeCommandId = null
            fail("无法发送时间同步请求")
        }
    }

    // 在 HandshakeClient.kt 中添加这个方法

    @SuppressLint("MissingPermission")
    private fun triggerDeviceStart(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(UUID.fromString(ProtoConfig.Service.SERVICE_UUID)) ?: run {
            log("triggerDeviceStart: service not found")
            return false
        }

        // 0x0012 对应 A001 (HANDSHAKE characteristic)
        val handshakeChar = service.getCharacteristic(
            UUID.fromString(ProtoConfig.Service.STATUS_CHAR_UUID)) ?: run {
            log("triggerDeviceStart: HANDSHAKE char not found")
            return false
        }

        log("Sending Read Request to HANDSHAKE (0x0012) to trigger device start...")
        val success = gatt.readCharacteristic(handshakeChar)
        log("Read Request sent: $success")
        return success
    }

    private fun fail(reason: String) {
        verificationCodeSent = null
        pendingSetTimeCommandId = null
        cancelTimeout()
        onEvent(HandshakeEvent.Failed(reason))
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let(handler::removeCallbacks)
        timeoutRunnable = null
    }


    private fun encodeHandshakeRequest(
        verificationCode: String,
        deviceCode: String,
        accountCode: Int
    ): ByteArray {
        val message = ByteArray(27)

        message[0] = HandshakeMessageType.HANDSHAKE_REQUEST.value.toByte()

        verificationCode
            .padEnd(6, '\u0000')
            .toByteArray(Charsets.UTF_8)
            .copyInto(message, 1)

        uuidToBytes(deviceCode).copyInto(message, 7)

        writeUInt32LE(message, accountCode, 23)

        return message
    }


    @OptIn(kotlin.ExperimentalStdlibApi::class)
    @SuppressLint("MissingPermission")
    private fun writeToTransferChar(gatt: BluetoothGatt, value: ByteArray): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_CONNECT
        else
            Manifest.permission.BLUETOOTH

        if (ActivityCompat.checkSelfPermission(context, perm)
            != PackageManager.PERMISSION_GRANTED
        ) {
            log("HS: no permission for writeCharacteristic")
            return false
        }

        val service = gatt.getService(UUID.fromString(ProtoConfig.Service.SERVICE_UUID))
            ?: run {
                log("HS: service not found for writeToTransferChar")
                return false
            }

        val transferChar = service.getCharacteristic(UUID.fromString(ProtoConfig.Service.TRANSFER_CHAR_UUID))
            ?: run {
                log("HS: TRANSFER char not found for writeToTransferChar")
                return false
            }

        // 关键：使用 Write Without Response，和官方一致
        log("HS: writeToTransferChar: char.writeType=${transferChar.writeType}, len=${value.size}")

        transferChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

        transferChar.value = value
        val ok = gatt.writeCharacteristic(transferChar)
        log("HS: writeToTransferChar: writeCharacteristic ok=$ok, value=${value.toHex()}")
        return ok
    }


    private fun uuidToBytes(uuid: String): ByteArray {
        val hex = uuid.replace("-", "")
        return hex.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    private fun writeUInt32LE(buffer: ByteArray, value: Int, offset: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun getOrCreateDeviceUUID(context: Context): String {
        val sp = context.getSharedPreferences("handshake_prefs", Context.MODE_PRIVATE)
        val saved = sp.getString("device_uuid", null)
        if (saved != null) return saved

        val newId = java.util.UUID.randomUUID().toString()
        sp.edit().putString("device_uuid", newId).apply()
        return newId
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it) }
}
