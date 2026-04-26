package com.example.nunarecorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.app.Service
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.nunarecorder.MainActivity
import com.example.nunarecorder.R
import com.example.nunarecorder.ble.BleAudioReassembler
import com.example.nunarecorder.ble.ProtoConfig
import java.io.File
import java.util.UUID

/**
 * 前台录音服务：负责在后台维持 BLE 连接，启用 A003 通知并把数据交给 BleAudioReassembler 写 opus 文件
 */
class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"

        private const val CHANNEL_ID = "nuna_recording_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.nunarecorder.action.START_RECORDING"
        const val ACTION_STOP = "com.example.nunarecorder.action.STOP_RECORDING"

        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
        const val EXTRA_DEVICE_NAME = "extra_device_name"

        private val SERVICE_UUID: UUID get() = UUID.fromString(ProtoConfig.Service.SERVICE_UUID)
        private val CHAR_UUID: UUID get() = UUID.fromString(ProtoConfig.Service.RECORDING_CHAR_UUID)
        private val CCCD_UUID: UUID get() = UUID.fromString(ProtoConfig.Service.CCCD_UUID)
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var reassembler: BleAudioReassembler? = null
    private var outputFile: File? = null
    private var recording = false

    private var currentDeviceName: String = "unknown"
    private var currentDeviceAddress: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                val name = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "unknown"
                Log.d(TAG, "onStartCommand: ACTION_START, addr=$address, name=$name")
                if (address.isNullOrEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                currentDeviceAddress = address
                currentDeviceName = name

                // 前台服务启动
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Connecting to $name...")
                )

                // 连接已由 MainActivity 建立，这里只负责从现有 gatt 开始录音，
                // 或者（更简单）我们在 Service 内重新连接一次
                connectAndStartRecording(address, name)
            }
            ACTION_STOP -> {
                stopRecordingInternal()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecordingInternal()
    }

    // ----------------- 核心：连接 & 开启录音 -----------------

    private fun connectAndStartRecording(deviceAddress: String, deviceName: String) {
        Log.d(TAG, "connectAndStartRecording: addr=$deviceAddress, name=$deviceName")
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter
        val device = adapter.getRemoteDevice(deviceAddress)

        // 重新连接 GATT（与 Activity 独立）
        bluetoothGatt = device.connectGatt(
            this,
            false,
            gattCallback
        )
    }

    private fun startRecordingOnGatt(gatt: BluetoothGatt) {
        if (recording) {
            Log.d(TAG, "startRecordingOnGatt: already recording, ignore")
            return
        }
        Log.d(TAG, "startRecordingOnGatt: preparing file & reassembler")
        // 准备输出文件
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.exists()) downloads.mkdirs()

        val safeName = currentDeviceName
            .replace("[^a-zA-Z0-9_-]".toRegex(), "_")
            .ifBlank { "unknown" }
        val timestamp = System.currentTimeMillis()
        outputFile = File(downloads, "nuna_${safeName}_$timestamp.opus")
        Log.d(TAG, "startRecordingOnGatt: outputFile=${outputFile?.absolutePath}")


        reassembler = BleAudioReassembler(outputFile!!) { msg ->
            Log.d(TAG, "Reassembler: $msg")
        }

        val service = gatt.getService(SERVICE_UUID)
        if (service == null) {
            updateNotification("Service not found")
            Log.e(TAG, "Service $SERVICE_UUID not found on device")
            return
        }
        val characteristic = service.getCharacteristic(CHAR_UUID)
        if (characteristic == null) {
            updateNotification("Characteristic not found")
            Log.e(TAG, "Characteristic $CHAR_UUID not found")
            return
        }

        Log.d(TAG, "Enabling notifications on $CHAR_UUID")
        enableNotifications(gatt, characteristic)
        recording = true
        updateNotification("Recording from $currentDeviceName")
    }

    private fun stopRecordingInternal() {
        try {
            recording = false
            reassembler?.close()
            reassembler = null
        } catch (_: Exception) {}

        try {
            bluetoothGatt?.close()
            bluetoothGatt = null
        } catch (_: Exception) {}
    }

    // ----------------- GATT 回调 -----------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            super.onConnectionStateChange(gatt, status, newState)
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                updateNotification("BLE error: $status")
                stopSelf()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                updateNotification("Discovering services...")
                Log.d(TAG, "STATE_CONNECTED, start discoverServices")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                updateNotification("Disconnected")
                Log.d(TAG, "STATE_DISCONNECTED, stopSelf")
                stopSelf()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            Log.d(TAG, "onServicesDiscovered: status=$status, services=${gatt.services?.size}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                updateNotification("Service discovery failed: $status")
                stopSelf()
                return
            }
            // 服务发现成功后，开始录音（开启 A003 通知）
            startRecordingOnGatt(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            Log.d(TAG, "onCharacteristicChanged: uuid=${characteristic.uuid}, len=${value.size}")
            if (characteristic.uuid == CHAR_UUID) {
                reassembler?.feed(value)
            }
        }
    }

    // ----------------- 通知开关 -----------------

    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        Log.d(TAG, "enableNotifications: setCharacteristicNotification=true")
        gatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (cccd != null) {
            Log.d(TAG, "enableNotifications: writing CCCD ENABLE_NOTIFICATION_VALUE")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccd)
        } else {
            Log.e(TAG, "enableNotifications: CCCD $CCCD_UUID not found")
        }
    }

    // ----------------- 前台通知 -----------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nuna Recording",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Recording audio from Nuna device"
            mgr.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)  // 先用应用图标占位
            .setContentTitle("Nuna Recording")
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID, buildNotification(content))
    }
}
