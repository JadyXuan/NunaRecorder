package com.example.nunarecorder.recording

import com.example.nunarecorder.ble.NunaProtocolInspector
import com.example.nunarecorder.session.SessionPaths
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.Base64

data class MmWaveCaptureStats(
    val packetCount: Long,
    val payloadBytes: Long,
    val malformedPackets: Long,
    val file: File?,
    val stateEventCount: Long,
    val stateFile: File?
)

sealed interface MmWaveCaptureResult {
    data object Inactive : MmWaveCaptureResult
    data object NotSensorData : MmWaveCaptureResult
    data object Malformed : MmWaveCaptureResult
    data object Queued : MmWaveCaptureResult
    data object Dropped : MmWaveCaptureResult
    data class Captured(val packetIndex: Long, val payloadBytes: Int) : MmWaveCaptureResult
    data class WriteFailed(val reason: String) : MmWaveCaptureResult
}

/**
 * Lossless session-side capture for A001 type 0x08 sensor notifications.
 *
 * The supplied protocol does not define the millimetre-wave payload schema. Each JSONL
 * row therefore stores both parsed transport fields and the full BLE protocol packet as
 * Base64. This makes future decoding possible without guessing field semantics today.
 */
class MmWaveSessionWriter(
    private val wallClockMs: () -> Long = System::currentTimeMillis
) {
    private var sessionDir: File? = null
    private var sessionStartedAtMs = 0L
    private var writer: BufferedWriter? = null
    private var stateWriter: BufferedWriter? = null
    private var file: File? = null
    private var stateFile: File? = null

    /**
     * 采集界面要实时读这两个计数，而写入发生在 BLE 回调线程上。
     * 加锁读会让 50 次/秒的写路径和每秒一次的 UI 读互相等，`@Volatile` 足够：
     * 计数只增不减，UI 读到的哪怕晚一格也无所谓。
     */
    @Volatile
    var packetCount = 0L
        private set

    @Volatile
    var stateEventCount = 0L
        private set

    private var payloadBytes = 0L
    private var malformedPackets = 0L
    private var packetsSinceFlush = 0
    private var lastFlushAtMs = 0L

    @Synchronized
    fun start(dir: File, startedAtMs: Long) {
        stop()
        sessionDir = dir
        sessionStartedAtMs = startedAtMs
        lastFlushAtMs = wallClockMs()
    }

    @Synchronized
    fun feed(
        raw: ByteArray,
        receivedAtMs: Long = wallClockMs(),
        sessionOffsetMs: Long = (receivedAtMs - sessionStartedAtMs).coerceAtLeast(0L)
    ): MmWaveCaptureResult {
        if (sessionDir == null) return MmWaveCaptureResult.Inactive
        val envelope = NunaProtocolInspector.parseEnvelope(raw)
            ?: return MmWaveCaptureResult.NotSensorData
        if (envelope.type != 0x08) return MmWaveCaptureResult.NotSensorData
        val packet = NunaProtocolInspector.parseSensor(raw)
        if (packet == null) {
            malformedPackets++
            return MmWaveCaptureResult.Malformed
        }

        return try {
            ensureWriter()
            val packetIndex = packetCount
            val row = JSONObject().apply {
                put("type", "mmwave_raw")
                put("format_version", 1)
                put("packet_index", packetIndex)
                put("received_at_ms", receivedAtMs)
                put("session_offset_ms", sessionOffsetMs)
                put("device_timestamp_ms", packet.timestampMs)
                put("sensor_type", packet.sensorType)
                put("payload_bytes", packet.payload.size)
                put("payload_base64", Base64.getEncoder().encodeToString(packet.payload))
                put("raw_packet_base64", Base64.getEncoder().encodeToString(packet.rawPacket))
                put("protocol_version", packet.protocolVersion)
                put("checksum", packet.checksum)
                put("checksum_class", packet.checksumClassification)
            }
            writer!!.apply {
                write(row.toString())
                newLine()
            }
            packetCount++
            payloadBytes += packet.payload.size
            packetsSinceFlush++
            flushIfNeeded(receivedAtMs)
            MmWaveCaptureResult.Captured(packetIndex, packet.payload.size)
        } catch (e: Exception) {
            MmWaveCaptureResult.WriteFailed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Records A001 0x13 transitions separately from raw 0x08 samples. */
    @Synchronized
    fun recordState(
        enabled: Boolean,
        requestedByApp: Boolean,
        receivedAtMs: Long,
        sessionOffsetMs: Long,
        source: String
    ) {
        if (sessionDir == null) return
        ensureStateWriter()
        val row = JSONObject().apply {
            put("type", "mmwave_state")
            put("format_version", 1)
            put("event_index", stateEventCount++)
            put("received_at_ms", receivedAtMs)
            put("session_offset_ms", sessionOffsetMs)
            put("enabled", enabled)
            put("state", if (enabled) "active" else if (requestedByApp) "sleeping" else "inactive")
            put("requested_by_app", requestedByApp)
            put("source", source)
        }
        stateWriter!!.apply {
            write(row.toString())
            newLine()
            flush()
        }
    }

    @Synchronized
    fun stop(): MmWaveCaptureStats {
        try {
            writer?.flush()
            writer?.close()
            stateWriter?.flush()
            stateWriter?.close()
        } catch (_: Exception) {
        }
        writer = null
        stateWriter = null
        val stats = MmWaveCaptureStats(
            packetCount,
            payloadBytes,
            malformedPackets,
            file,
            stateEventCount,
            stateFile
        )
        sessionDir = null
        sessionStartedAtMs = 0L
        file = null
        stateFile = null
        packetCount = 0L
        payloadBytes = 0L
        malformedPackets = 0L
        packetsSinceFlush = 0
        stateEventCount = 0L
        lastFlushAtMs = 0L
        return stats
    }

    private fun ensureWriter() {
        if (writer != null) return
        val dir = requireNotNull(sessionDir)
        val target = SessionPaths.mmWaveFile(dir)
        target.parentFile?.mkdirs()
        writer = BufferedWriter(
            OutputStreamWriter(FileOutputStream(target, false), Charsets.UTF_8),
            32 * 1024
        )
        file = target
    }

    private fun ensureStateWriter() {
        if (stateWriter != null) return
        val dir = requireNotNull(sessionDir)
        val target = SessionPaths.mmWaveStateFile(dir)
        target.parentFile?.mkdirs()
        stateWriter = BufferedWriter(
            OutputStreamWriter(FileOutputStream(target, false), Charsets.UTF_8),
            8 * 1024
        )
        stateFile = target
    }

    private fun flushIfNeeded(nowMs: Long) {
        if (packetsSinceFlush < 16 && nowMs - lastFlushAtMs < 1_000L) return
        writer?.flush()
        packetsSinceFlush = 0
        lastFlushAtMs = nowMs
    }
}
