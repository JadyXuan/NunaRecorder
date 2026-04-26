# Wearable 即插即用包 (com.example.wearable)

本包提供三个接口及默认实现，供 `com.example.nunapin` 等宿主 App 替换 mock 实现，对接 Nuna 可穿戴设备的录音、音频块与转写。

## 接口

1. **WearableRecordingController** — 开始/停止可穿戴端录音（主开关）
2. **AudioChunkProvider** — 在录音过程中按重叠时间窗口提供原始 WAV 音频块
3. **TranscriptionProvider** — 在录音过程中按句提供转写（本实现为占位，不回调）

## 连接配置（不扫描设备）

设备信息在代码中配置，不提供扫描/选择 UI。使用 **WearableConnectionConfig** 指定设备地址与握手校验码：

```kotlin
val config = WearableConnectionConfig(
    deviceAddress = "AA:BB:CC:DD:EE:FF",
    verificationCode = "123456"
)
val (controller, chunkProvider, transcriptionProvider) = WearableServiceFactory.createAll(context, config)
```

或仅设置地址（校验码默认 "123456"）：

```kotlin
val service = WearableServiceFactory.create(context)
(service as? NunaWearableServiceImpl)?.setDeviceAddress("AA:BB:CC:DD:EE:FF")
```

## 内部流程（接口 1 + 2）

调用 `startRecording(onReady, onError)` 后，内部顺序执行：

1. 用配置中的 **deviceAddress** 连接指定 Nuna 设备（BLE GATT）
2. 服务发现后对 **A002 (TRANSFER)** 开启 Notify，发送握手请求，收到 HANDSHAKE_RESPONSE 后发送 HANDSHAKE_COMPLETED 与 SET_TIME
3. 收到 CONTROL_RESPONSE（设时成功）后对 **A001** 发 read 触发设备，再对 **A003 (RECORDING)** 开启 Notify
4. 此时认为「已开始采集」→ 调用 **onReady()**
5. A003 持续推送 Opus → **BleStreamOpusReassembler** 同 `BleAudioReassembler` 解析并顺序追加；**实时**按 80 字节一包解码为 **mono PCM**；PCM 满 **chunkDurationMs**（默认 10s）封 WAV 回调，下一窗步长 **chunkDurationMs − overlapDurationMs**（默认 8s），重叠 **overlapDurationMs**（默认 2s）。若 `WearableConnectionConfig.dumpOverlapWavToDebugDir == true` 或 `setDumpOverlapWavToDebugDir(true)`，则同时写入 `files/wearable_wav_debug/overlap_*.wav`；否则仅 **onAudioChunkReady**。

## 数据类型

- **com.example.wearable.data.AudioChunk**：`data: ByteArray`（完整 WAV，44 字节头 + 16 kHz 单声道 16-bit PCM）, `startTimeMs: Long`, `endTimeMs: Long`（墙钟毫秒）

## 获取实现

```kotlin
val config = WearableConnectionConfig(deviceAddress = "AA:BB:CC:DD:EE:FF")
val (controller, chunkProvider, transcriptionProvider) = WearableServiceFactory.createAll(context, config)
controller.startRecording(onReady = { ... }, onError = { ... })
chunkProvider.setListener { chunk -> ... }
chunkProvider.startChunkDelivery(60_000, 10_000)
```

## 生命周期（与 spec 一致）

1. 用户按「开始录音」→ `WearableRecordingController.startRecording(onReady, onError)`  
   → 内部完成连接、握手、开启 A003 订阅 → `onReady` 回调  
2. `AudioChunkProvider.setListener(...)` 后 `startChunkDelivery(60000, 10000)`  
3. `TranscriptionProvider.setListener(...)` 后 `startTranscription()`  
4. 用户按「停止录音」→ `TranscriptionProvider.stopTranscription()` →  
   `AudioChunkProvider.stopChunkDelivery()` → `WearableRecordingController.stopRecording()`

## 依赖

仅依赖 Android SDK 与 `org.concentus`（Opus 解码），不依赖 `com.example.nunarecorder`。  
将本 package 拷贝到其他工程时，需在目标工程中引入 `org.concentus` 及蓝牙权限。
