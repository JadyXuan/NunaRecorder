package com.example.wearable

import com.example.wearable.data.AudioChunk

/**
 * 在录音过程中提供可重叠的原始音频块，供音频 LLM 做场景检测与摘要。
 */
interface AudioChunkProvider {

    /**
     * 应用注册以接收音频块的监听器。
     */
    fun interface AudioChunkListener {
        /**
         * 每准备好一个新音频块时调用。
         * 可能在任何线程调用，由应用方保证线程安全。
         */
        fun onAudioChunkReady(chunk: AudioChunk)
    }

    /**
     * 注册接收音频块的监听器。
     * 需在 [startChunkDelivery] 前调用；重复调用会替换之前的监听器。
     */
    fun setListener(listener: AudioChunkListener?)

    /**
     * 开始交付音频块。
     *
     * 时间约定：
     *   • 每块覆盖 [chunkDurationMs] 毫秒
     *   • 相邻块重叠 [overlapDurationMs] 毫秒，即若块 N 从 T 开始，块 N+1 从 T + (chunkDurationMs − overlapDurationMs) 开始
     *   • [AudioChunk.startTimeMs] 与 [AudioChunk.endTimeMs] 须为墙钟时间（毫秒，epoch）
     *
     * 常见默认：chunkDurationMs = 60_000，overlapDurationMs = 10_000。
     *
     * 实现说明（NunaWearableServiceImpl）：A003 按 BleAudioReassembler 逻辑重组后
     * 实时 80 字节 Opus 包解码为 mono PCM 时间轴；当累计 PCM 达到 [chunkDurationMs]
     * 即封 WAV 回调；下一窗从时间轴上前移 [chunkDurationMs − overlapDurationMs]，
     * 保证固定毫秒重叠。默认 10_000 / 2_000，可按对接改为 60_000 / 10_000。
     *
     * 音频格式：
     *   • [AudioChunk.data] 须为完整 WAV（含 44 字节头）
     *   • 推荐：16 kHz，单声道，16-bit PCM
     *
     * @param chunkDurationMs 每块时长（毫秒）
     * @param overlapDurationMs 相邻块重叠时长（毫秒）
     */
    fun startChunkDelivery(
        chunkDurationMs: Long,
        overlapDurationMs: Long
    )

    /**
     * 停止交付音频块。返回后不再触发 [AudioChunkListener]。
     */
    fun stopChunkDelivery()
}
