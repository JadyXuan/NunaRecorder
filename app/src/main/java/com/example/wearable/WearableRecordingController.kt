package com.example.wearable

/**
 * 可穿戴设备录音总控：开始/停止录音。
 * 开始后设备应通过 [AudioChunkProvider] 提供原始音频块，通过 [TranscriptionProvider] 提供转写句子。
 */
interface WearableRecordingController {

    /**
     * 在可穿戴设备上开始录音。
     *
     * 返回后，可穿戴设备应：
     *   • 通过 [AudioChunkProvider] 开始提供原始音频块
     *   • 通过 [TranscriptionProvider] 开始提供转写句子
     *
     * 可在 Android 主线程安全调用。耗时连接（如 BLE 握手）应异步进行，
     * 使用 [onReady] 表示已开始录音，或 [onError] 表示失败。
     *
     * @param onReady 开始实际录音时调用（可能在任何线程）
     * @param onError 无法开始录音时调用（可能在任何线程），消息应为人可读
     */
    fun startRecording(
        onReady: () -> Unit = {},
        onError: (errorMessage: String) -> Unit = {}
    )

    /**
     * 停止可穿戴设备上的录音。
     *
     * 调用后：
     *   • 不再交付新的音频块
     *   • 不再触发转写回调
     *   • 应释放与录音相关的可穿戴/BLE/网络资源
     *
     * 若从未调用过 start，也可安全调用（视为 no-op）。
     */
    fun stopRecording()

    /**
     * @return 当前是否正在录音
     */
    fun isRecording(): Boolean
}
