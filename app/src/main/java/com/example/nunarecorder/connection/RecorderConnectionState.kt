package com.example.nunarecorder.connection

enum class RecorderConnectionPhase {
    DISCONNECTED,
    CONNECTING,
    DISCOVERING_SERVICES,
    PREPARING_HANDSHAKE,
    HANDSHAKE_CHANNEL_READY,
    HANDSHAKING,
    READY,
    STARTING_RECORDING,
    RECORDING,
    AUDIO_STALLED,
    STOPPING_RECORDING,
    DISCONNECTING,
    ERROR
}

data class RecorderConnectionState(
    val phase: RecorderConnectionPhase = RecorderConnectionPhase.DISCONNECTED,
    val deviceLabel: String? = null,
    val detail: String = "请选择 Nuna 设备并完成连接"
) {
    val isConnected: Boolean
        get() = phase in setOf(
            RecorderConnectionPhase.DISCOVERING_SERVICES,
            RecorderConnectionPhase.PREPARING_HANDSHAKE,
            RecorderConnectionPhase.HANDSHAKE_CHANNEL_READY,
            RecorderConnectionPhase.HANDSHAKING,
            RecorderConnectionPhase.READY,
            RecorderConnectionPhase.STARTING_RECORDING,
            RecorderConnectionPhase.RECORDING,
            RecorderConnectionPhase.AUDIO_STALLED,
            RecorderConnectionPhase.STOPPING_RECORDING,
            RecorderConnectionPhase.DISCONNECTING
        )

    val isReady: Boolean get() = phase == RecorderConnectionPhase.READY
    val isRecording: Boolean
        get() = phase == RecorderConnectionPhase.RECORDING ||
            phase == RecorderConnectionPhase.AUDIO_STALLED
    val canStartRecording: Boolean get() = phase == RecorderConnectionPhase.READY
    val canStopRecording: Boolean
        get() = phase == RecorderConnectionPhase.STARTING_RECORDING || isRecording
    val canDisconnect: Boolean get() = phase == RecorderConnectionPhase.READY
    val canConnect: Boolean
        get() = phase == RecorderConnectionPhase.DISCONNECTED || phase == RecorderConnectionPhase.ERROR
    val canScanOrSelect: Boolean get() = canConnect

    val title: String
        get() = when (phase) {
            RecorderConnectionPhase.DISCONNECTED -> "未连接"
            RecorderConnectionPhase.CONNECTING -> "正在连接"
            RecorderConnectionPhase.DISCOVERING_SERVICES -> "正在初始化设备"
            RecorderConnectionPhase.PREPARING_HANDSHAKE -> "正在准备握手"
            RecorderConnectionPhase.HANDSHAKE_CHANNEL_READY -> "握手通道已就绪"
            RecorderConnectionPhase.HANDSHAKING -> "正在验证设备"
            RecorderConnectionPhase.READY -> "设备已就绪"
            RecorderConnectionPhase.STARTING_RECORDING -> "正在启动录制"
            RecorderConnectionPhase.RECORDING -> "正在录制"
            RecorderConnectionPhase.AUDIO_STALLED -> "音频流停滞"
            RecorderConnectionPhase.STOPPING_RECORDING -> "正在停止录制"
            RecorderConnectionPhase.DISCONNECTING -> "正在断开"
            RecorderConnectionPhase.ERROR -> "连接失败"
        }
}

sealed interface RecorderConnectionEvent {
    data class ConnectRequested(val deviceLabel: String) : RecorderConnectionEvent
    data class GattConnected(val deviceLabel: String) : RecorderConnectionEvent
    data object ServicesDiscovered : RecorderConnectionEvent
    data object HandshakeNotificationsReady : RecorderConnectionEvent
    data object HandshakeStarted : RecorderConnectionEvent
    data object HandshakeSucceeded : RecorderConnectionEvent
    data class HandshakeFailed(val reason: String) : RecorderConnectionEvent
    data object RecordingStartRequested : RecorderConnectionEvent
    data object RecordingTransportReady : RecorderConnectionEvent
    data object RecordingStarted : RecorderConnectionEvent
    data class RecordingStartFailed(val reason: String) : RecorderConnectionEvent
    data object AudioStalled : RecorderConnectionEvent
    data object AudioRecovered : RecorderConnectionEvent
    data class RecordingRecoveryStarted(val reason: String) : RecorderConnectionEvent
    data object StopRequested : RecorderConnectionEvent
    data class RecordingStopped(val detail: String) : RecorderConnectionEvent
    data object DisconnectRequested : RecorderConnectionEvent
    data object Disconnected : RecorderConnectionEvent
    data class ConnectionFailed(val reason: String) : RecorderConnectionEvent
}

data class RecorderTransition(
    val state: RecorderConnectionState,
    val accepted: Boolean
)

/** Pure reducer used by both the Activity and unit tests. */
object RecorderConnectionReducer {
    fun reduce(
        current: RecorderConnectionState,
        event: RecorderConnectionEvent
    ): RecorderTransition {
        fun accepted(
            phase: RecorderConnectionPhase,
            detail: String,
            deviceLabel: String? = current.deviceLabel
        ) = RecorderTransition(RecorderConnectionState(phase, deviceLabel, detail), true)

        fun rejected() = RecorderTransition(current, false)

        return when (event) {
            is RecorderConnectionEvent.ConnectRequested -> {
                if (!current.canConnect) rejected()
                else accepted(
                    RecorderConnectionPhase.CONNECTING,
                    "正在连接 ${event.deviceLabel}",
                    event.deviceLabel
                )
            }
            is RecorderConnectionEvent.GattConnected -> {
                if (current.phase != RecorderConnectionPhase.CONNECTING) rejected()
                else accepted(
                    RecorderConnectionPhase.DISCOVERING_SERVICES,
                    "已连接 ${event.deviceLabel}，正在发现服务",
                    event.deviceLabel
                )
            }
            RecorderConnectionEvent.ServicesDiscovered -> {
                if (current.phase != RecorderConnectionPhase.DISCOVERING_SERVICES) rejected()
                else accepted(RecorderConnectionPhase.PREPARING_HANDSHAKE, "正在启用握手通知")
            }
            RecorderConnectionEvent.HandshakeNotificationsReady -> {
                if (current.phase != RecorderConnectionPhase.PREPARING_HANDSHAKE) rejected()
                else accepted(RecorderConnectionPhase.HANDSHAKE_CHANNEL_READY, "即将验证设备")
            }
            RecorderConnectionEvent.HandshakeStarted -> {
                if (current.phase != RecorderConnectionPhase.HANDSHAKE_CHANNEL_READY) rejected()
                else accepted(RecorderConnectionPhase.HANDSHAKING, "正在验证设备并同步时间")
            }
            RecorderConnectionEvent.HandshakeSucceeded -> {
                if (current.phase != RecorderConnectionPhase.HANDSHAKING) rejected()
                else accepted(
                    RecorderConnectionPhase.READY,
                    "${current.deviceLabel ?: "Nuna"} · 可以开始录制"
                )
            }
            is RecorderConnectionEvent.HandshakeFailed -> {
                if (current.phase != RecorderConnectionPhase.HANDSHAKING &&
                    current.phase != RecorderConnectionPhase.PREPARING_HANDSHAKE &&
                    current.phase != RecorderConnectionPhase.HANDSHAKE_CHANNEL_READY
                ) rejected()
                else accepted(RecorderConnectionPhase.ERROR, "握手失败：${event.reason}")
            }
            RecorderConnectionEvent.RecordingStartRequested -> {
                if (!current.canStartRecording) rejected()
                else accepted(
                    RecorderConnectionPhase.STARTING_RECORDING,
                    "正在订阅音频并请求设备开始录音"
                )
            }
            RecorderConnectionEvent.RecordingTransportReady -> {
                if (current.phase != RecorderConnectionPhase.STARTING_RECORDING) rejected()
                else accepted(
                    RecorderConnectionPhase.STARTING_RECORDING,
                    "设备已接受录音命令，正在等待首个音频包"
                )
            }
            RecorderConnectionEvent.RecordingStarted -> {
                if (current.phase != RecorderConnectionPhase.STARTING_RECORDING) rejected()
                else accepted(
                    RecorderConnectionPhase.RECORDING,
                    "${current.deviceLabel ?: "Nuna"} · 音频流正常"
                )
            }
            is RecorderConnectionEvent.RecordingStartFailed -> {
                if (current.phase != RecorderConnectionPhase.STARTING_RECORDING) rejected()
                else accepted(
                    RecorderConnectionPhase.READY,
                    "录制启动失败：${event.reason}，可以重试"
                )
            }
            RecorderConnectionEvent.AudioStalled -> {
                if (current.phase != RecorderConnectionPhase.RECORDING) rejected()
                else accepted(RecorderConnectionPhase.AUDIO_STALLED, "BLE 仍连接，正在等待音频恢复")
            }
            RecorderConnectionEvent.AudioRecovered -> {
                if (current.phase != RecorderConnectionPhase.AUDIO_STALLED) rejected()
                else accepted(RecorderConnectionPhase.RECORDING, "音频流已恢复")
            }
            is RecorderConnectionEvent.RecordingRecoveryStarted -> {
                if (current.phase != RecorderConnectionPhase.STARTING_RECORDING) rejected()
                else accepted(
                    RecorderConnectionPhase.STOPPING_RECORDING,
                    "${event.reason}；正在复位设备录音状态"
                )
            }
            RecorderConnectionEvent.StopRequested -> {
                if (!current.canStopRecording) rejected()
                else accepted(RecorderConnectionPhase.STOPPING_RECORDING, "正在停止设备录音")
            }
            is RecorderConnectionEvent.RecordingStopped -> {
                if (current.phase != RecorderConnectionPhase.STOPPING_RECORDING) rejected()
                else accepted(RecorderConnectionPhase.READY, event.detail)
            }
            RecorderConnectionEvent.DisconnectRequested -> {
                if (!current.canDisconnect) rejected()
                else accepted(RecorderConnectionPhase.DISCONNECTING, "正在断开设备")
            }
            RecorderConnectionEvent.Disconnected -> accepted(
                RecorderConnectionPhase.DISCONNECTED,
                "请选择 Nuna 设备并完成连接",
                null
            )
            is RecorderConnectionEvent.ConnectionFailed -> accepted(
                RecorderConnectionPhase.ERROR,
                event.reason
            )
        }
    }
}
