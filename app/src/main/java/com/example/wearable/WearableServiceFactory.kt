package com.example.wearable

import android.content.Context
import com.example.wearable.impl.NunaWearableServiceImpl

/**
 * 创建可穿戴录音 / 音频块 / 转写实现，便于即插即用。
 *
 * 使用示例（在代码中配置设备，不扫描），一次性启用三个服务：
 * ```
 * val config = WearableConnectionConfig(
 *     deviceAddress = "AA:BB:CC:DD:EE:FF",
 *     verificationCode = "123456",
 *     dumpOverlapWavToDebugDir = false,
 *     deepgramApiKey = "your-deepgram-key"  // 为空则不启用转写
 * )
 *
 * // 仅录音控制（不需要 chunk / 转写时）：
 * val controller: WearableRecordingController =
 *     WearableServiceFactory.create(context, config)
 *
 * // 或一次性拿到三个接口：录音控制 + 音频块 + 转写
 * val (recording, chunks, transcription) =
 *     WearableServiceFactory.createAll(context, config)
 *
 * // 启动录音
 * recording.startRecording(
 *     onReady = { /* A003 已订阅，可开始消费音频 */ },
 *     onError = { msg -> /* 处理错误 */ }
 * )
 *
 * // 启动 AudioChunkProvider：例如 60s 窗 / 10s 重叠
 * chunks.setListener { chunk ->
 *     // 使用 chunk.data (完整 WAV) 以及 chunk.startTimeMs / endTimeMs（墙钟毫秒）
 * }
 * chunks.startChunkDelivery(chunkDurationMs = 60_000, overlapDurationMs = 10_000)
 *
 * // 启动 TranscriptionProvider（需 config.deepgramApiKey 非空）
 * transcription.setListener { text, startMs, endMs ->
 *     // 一句（EndOfTurn）转写及其对应的墙钟时间范围
 * }
 * transcription.startTranscription()
 * ```
 */
object WearableServiceFactory {

    /**
     * 使用连接配置创建录音控制实现。
     *
     * @param context Android 上下文（内部自动使用 applicationContext）
     * @param config  可穿戴连接与转写配置：
     *  - [WearableConnectionConfig.deviceAddress]：Nuna 设备 MAC 地址
     *  - [WearableConnectionConfig.verificationCode]：握手校验码
     *  - [WearableConnectionConfig.dumpOverlapWavToDebugDir]：是否将 overlap WAV 写入 debug 目录
     *  - [WearableConnectionConfig.deepgramApiKey]：Deepgram listen-flux API Key（为空则不启用转写）
     */
    @JvmStatic
    fun create(context: Context, config: WearableConnectionConfig): WearableRecordingController {
        val impl = NunaWearableServiceImpl(context.applicationContext)
        impl.setConnectionConfig(config)
        return impl
    }

    /**
     * 创建并转为三接口视图：录音控制 + 音频块提供者 + 转写提供者。
     *
     * 三个接口共享同一个 [NunaWearableServiceImpl] 实例，配置与状态一致：
     *  - 录音控制：通过 [WearableRecordingController] 控制连接、开始/停止录音
     *  - 音频块：通过 [AudioChunkProvider] 获取按窗口切分的 WAV 块（含重叠），用于音频 LLM
     *  - 转写：通过 [TranscriptionProvider] 获取 Deepgram listen-flux 的按句转写结果
     *
     * @param context Android 上下文（内部自动使用 applicationContext）
     * @param config  可穿戴连接与转写配置，含 Deepgram API Key 等
     */
    @JvmStatic
    fun createAll(context: Context, config: WearableConnectionConfig): Triple<WearableRecordingController, AudioChunkProvider, TranscriptionProvider> {
        val impl = NunaWearableServiceImpl(context.applicationContext)
        impl.setConnectionConfig(config)
        return Triple(impl, impl, impl)
    }
}
