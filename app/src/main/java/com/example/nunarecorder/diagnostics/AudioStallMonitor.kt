package com.example.nunarecorder.diagnostics

enum class AudioStallEventType {
    WARNING,
    STALLED,
    RECOVERED
}

data class AudioStallEvent(
    val type: AudioStallEventType,
    val silenceMs: Long
)

/**
 * Tracks notification silence independently from the later Android GATT disconnect callback.
 * All time values must come from the same monotonic clock.
 */
class AudioStallMonitor(
    private val warningAfterMs: Long = 1_000L,
    private val stalledAfterMs: Long = 3_000L
) {
    private enum class State { STOPPED, HEALTHY, WARNING, STALLED }

    private var state = State.STOPPED
    private var lastPacketMs = 0L

    init {
        require(warningAfterMs > 0L)
        require(stalledAfterMs > warningAfterMs)
    }

    @Synchronized
    fun start(nowMs: Long) {
        lastPacketMs = nowMs
        state = State.HEALTHY
    }

    @Synchronized
    fun stop() {
        state = State.STOPPED
    }

    @Synchronized
    fun onPacket(nowMs: Long): AudioStallEvent? {
        if (state == State.STOPPED) return null
        val silenceMs = (nowMs - lastPacketMs).coerceAtLeast(0L)
        val recovered = state == State.WARNING || state == State.STALLED
        lastPacketMs = nowMs
        state = State.HEALTHY
        return if (recovered) AudioStallEvent(AudioStallEventType.RECOVERED, silenceMs) else null
    }

    @Synchronized
    fun check(nowMs: Long): AudioStallEvent? {
        if (state == State.STOPPED) return null
        val silenceMs = (nowMs - lastPacketMs).coerceAtLeast(0L)
        return when {
            silenceMs >= stalledAfterMs && state != State.STALLED -> {
                state = State.STALLED
                AudioStallEvent(AudioStallEventType.STALLED, silenceMs)
            }
            silenceMs >= warningAfterMs && state == State.HEALTHY -> {
                state = State.WARNING
                AudioStallEvent(AudioStallEventType.WARNING, silenceMs)
            }
            else -> null
        }
    }
}
