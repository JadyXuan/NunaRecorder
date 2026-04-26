package com.example.wearable

/**
 * 可穿戴设备连接配置（在代码中配置，不扫描设备）。
 *
 * @param deviceAddress  Nuna 设备蓝牙 MAC 地址，如 "AA:BB:CC:DD:EE:FF"
 * @param verificationCode 握手校验码，须与设备端一致，默认 "123456"
 * @param dumpOverlapWavToDebugDir 为 true 时，每次交付的 overlap chunk 会写入 files/wearable_wav_debug/overlap_*.wav；为 false 则仅通过 [AudioChunkProvider] 回调 [AudioChunk]，不落盘
 * @param deepgramApiKey Deepgram API Key，用于 listen-flux 实时转写；为 null 时 [TranscriptionProvider.startTranscription] 不建立连接
 */
data class WearableConnectionConfig(
    val deviceAddress: String,
    val verificationCode: String = "123456",
    val dumpOverlapWavToDebugDir: Boolean = true,
    val deepgramApiKey: String? = "e7f545be263d0b4ad19cbd1983a8c84809ff4406"
)
