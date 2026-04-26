package com.example.wearable

/**
 * 在录音过程中按句（或短句）提供实时转写，供文本 LLM 做意图抽取。
 */
interface TranscriptionProvider {

    /**
     * 应用注册以接收转写结果的监听器。
     */
    fun interface TranscriptionListener {
        /**
         * 每得到一句（或一段短句）转写时调用。
         * 可能在任何线程调用，由应用方保证线程安全。
         *
         * @param text 转写文本，建议为一句或短句（约 5–40 词）
         * @param startTimeMs 该句开始的墙钟时间（毫秒，epoch）
         * @param endTimeMs 该句结束的墙钟时间（毫秒，epoch）；若无法提供可等于 startTimeMs
         */
        fun onTranscriptionReady(
            text: String,
            startTimeMs: Long,
            endTimeMs: Long
        )
    }

    /**
     * 注册接收转写结果的监听器。
     * 需在 [startTranscription] 前调用；重复调用会替换之前的监听器。
     */
    fun setListener(listener: TranscriptionListener?)

    /**
     * 开始交付转写结果（一般在可穿戴已开始录音后调用）。
     */
    fun startTranscription()

    /**
     * 停止交付转写结果。返回后不再触发 [TranscriptionListener]。
     */
    fun stopTranscription()
}
