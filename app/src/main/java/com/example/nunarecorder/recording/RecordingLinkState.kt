package com.example.nunarecorder.recording

enum class LinkPhase {
    /** 没在录，也不打算录 */
    IDLE,

    /** 正在建立 GATT / 握手 */
    CONNECTING,

    /** GATT 已连接，还没订阅到音频特征 */
    CONNECTED,

    /** 已订阅 A003，链路应当在推音频 */
    RECORDING,

    /** 断了，正在自动重连；会话保持打开 */
    RECONNECTING
}

/**
 * 录制链路的对外状态。UI 只读这一个对象。
 *
 * 2026-07-31 实测里佩戴者看到的是「录制中」，而链路其实早就断了；按下停止后又一直停在
 * 「录制中」。两个症状同源：录制状态散在 `MainActivity.recording`、
 * `viewModel.connectionStatus` 和 GATT 回调三处，谁都可能忘了更新另外两个。
 * 这里把它收敛成一个由 [RecordingStateMachine] 独占写入的值。
 */
data class LinkStatus(
    val phase: LinkPhase = LinkPhase.IDLE,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    /** 最近一次断连或失败的原因，供状态面板和导出日志使用 */
    val reason: String? = null,
    val reconnectAttempt: Int = 0,
    /** 下次重连还有多久（毫秒），仅 [LinkPhase.RECONNECTING] 有意义 */
    val nextRetryInMs: Long = 0L,
    /** 用户已按下停止；任何迟到的 GATT 回调都不得把状态拉回录制 */
    val stopRequested: Boolean = false,
    /** 设备电量 0–100；null = 还没读到 */
    val batteryPercent: Int? = null
) {
    /** 会话是否还开着（含重连中） */
    val isSessionActive: Boolean
        get() = phase != LinkPhase.IDLE

    /** 链路此刻是否真的在收音频。UI 必须用这个，而不是「有没有按过开始」 */
    val isStreaming: Boolean
        get() = phase == LinkPhase.RECORDING
}

/**
 * 纯状态机，无 Android 依赖。
 *
 * 核心不变量：[onStopRequested] 之后，任何迟到的回调都不能让状态回到
 * [LinkPhase.RECORDING] 或 [LinkPhase.RECONNECTING]。停止就是停止。
 */
class RecordingStateMachine {

    var status: LinkStatus = LinkStatus()
        private set

    fun onStartRequested(deviceName: String?, deviceAddress: String?): LinkStatus = update {
        LinkStatus(
            phase = LinkPhase.CONNECTING,
            deviceName = deviceName,
            deviceAddress = deviceAddress,
            stopRequested = false
        )
    }

    fun onGattConnected(): LinkStatus = updateUnlessStopped {
        it.copy(phase = LinkPhase.CONNECTED, reason = null, nextRetryInMs = 0L)
    }

    /** A003 CCCD 写入成功，设备应当开始推流 */
    fun onAudioSubscribed(): LinkStatus = updateUnlessStopped {
        it.copy(
            phase = LinkPhase.RECORDING,
            reason = null,
            reconnectAttempt = 0,
            nextRetryInMs = 0L
        )
    }

    /**
     * 链路断开。会话保持打开，等待自动重连——除非用户已经按了停止。
     */
    fun onDisconnected(reason: String): LinkStatus = updateUnlessStopped {
        it.copy(phase = LinkPhase.RECONNECTING, reason = reason)
    }

    fun onReconnectScheduled(attempt: Int, delayMs: Long): LinkStatus = updateUnlessStopped {
        it.copy(
            phase = LinkPhase.RECONNECTING,
            reconnectAttempt = attempt,
            nextRetryInMs = delayMs
        )
    }

    fun onBatteryLevel(percent: Int): LinkStatus = update { it.copy(batteryPercent = percent) }

    fun onReconnecting(): LinkStatus = updateUnlessStopped {
        it.copy(phase = LinkPhase.CONNECTING, nextRetryInMs = 0L)
    }

    /** 用户主动停止。这是唯一能回到 [LinkPhase.IDLE] 的入口。 */
    fun onStopRequested(): LinkStatus = update {
        LinkStatus(
            phase = LinkPhase.IDLE,
            deviceName = status.deviceName,
            deviceAddress = status.deviceAddress,
            reason = null,
            stopRequested = true
        )
    }

    private inline fun update(block: (LinkStatus) -> LinkStatus): LinkStatus {
        status = block(status)
        return status
    }

    private inline fun updateUnlessStopped(block: (LinkStatus) -> LinkStatus): LinkStatus {
        if (status.stopRequested || status.phase == LinkPhase.IDLE) return status
        status = block(status)
        return status
    }
}
