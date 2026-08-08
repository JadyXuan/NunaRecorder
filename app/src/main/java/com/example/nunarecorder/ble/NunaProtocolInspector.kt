package com.example.nunarecorder.ble

/**
 * Read-only protocol inspection helpers for comparing live Nuna traffic with the
 * supplied v1.4.4 protocol draft. These helpers never reject traffic: the product
 * firmware currently accepts the legacy fixed 0x1234 checksum, while the draft
 * describes CRC-16/XMODEM without defining its exact byte range.
 */
object NunaProtocolInspector {
    private const val ENVELOPE_BYTES = 7
    private const val AUDIO_HEADER_BYTES = 14

    data class Envelope(
        val type: Int,
        val declaredDataLength: Int,
        val version: Int,
        val checksum: Int,
        val data: ByteArray,
        val trailingBytes: Int,
        val crcData: Int,
        val crcHeaderAndData: Int,
        val crcZeroedEnvelope: Int
    ) {
        val checksumClassification: String
            get() = when {
                checksum == 0x1234 -> "legacy_fixed_1234"
                checksum == crcData -> "crc16_xmodem_data"
                checksum == crcHeaderAndData -> "crc16_xmodem_header_data"
                checksum == crcZeroedEnvelope -> "crc16_xmodem_zeroed_envelope"
                else -> "unknown"
            }
    }

    data class StatusMessage(
        val name: String,
        val fields: Map<String, Any?>
    )

    data class AudioPacket(
        val frameId: Int,
        val frameSize: Int,
        val chunkId: Int,
        val totalChunks: Int,
        val timestampMs: Long,
        val payloadBytes: Int,
        val opusFramesInPayload: Int?,
        val payloadMatchesFrameSize: Boolean
    )

    fun parseEnvelope(raw: ByteArray): Envelope? {
        if (raw.size < ENVELOPE_BYTES || u8(raw[0]) != ProtoConfig.Message.HEADER) return null
        val length = u16(raw, 2)
        if (length < 0 || raw.size < ENVELOPE_BYTES + length) return null
        val data = raw.copyOfRange(ENVELOPE_BYTES, ENVELOPE_BYTES + length)
        val headerAndData = raw.copyOfRange(0, 5) + data
        val zeroedEnvelope = raw.copyOfRange(0, ENVELOPE_BYTES + length).also {
            it[5] = 0
            it[6] = 0
        }
        return Envelope(
            type = u8(raw[1]),
            declaredDataLength = length,
            version = u8(raw[4]),
            checksum = u16(raw, 5),
            data = data,
            trailingBytes = raw.size - ENVELOPE_BYTES - length,
            crcData = crc16Xmodem(data),
            crcHeaderAndData = crc16Xmodem(headerAndData),
            crcZeroedEnvelope = crc16Xmodem(zeroedEnvelope)
        )
    }

    fun parseStatus(envelope: Envelope): StatusMessage {
        val data = envelope.data
        return when (envelope.type) {
            0x02 -> parseRunningStatus(data)
            0x03 -> oneByte("battery_level", "percent", data)
            0x04 -> oneByte("vibration_strength", "strength", data)
            0x05 -> parseDeviceInfo(data)
            0x08 -> parseSensorData(data)
            0x09 -> StatusMessage(
                "vibration_record",
                mapOf("payload_bytes" to data.size, "payload_hex" to data.toBoundedHex())
            )
            0x11 -> oneByte("recording_status", "recording", data) { it == 1 }
            0x13 -> oneByte("radar_status", "enabled", data) { it == 1 }
            0x15 -> oneByte("heartbeat", "sequence", data)
            0x16 -> oneByte("wearing_status", "wearing", data) { it == 1 }
            0x17 -> oneByte("wear_detection_status", "enabled", data) { it == 1 }
            else -> StatusMessage(
                "unknown_0x${envelope.type.toString(16).padStart(2, '0')}",
                mapOf("payload_bytes" to data.size, "payload_hex" to data.toBoundedHex())
            )
        }
    }

    fun parseAudio(raw: ByteArray): AudioPacket? {
        val envelope = parseEnvelope(raw) ?: return null
        if (envelope.type != 0x10 || envelope.data.size < AUDIO_HEADER_BYTES) return null
        val data = envelope.data
        val frameSize = u16(data, 2)
        val payloadBytes = data.size - AUDIO_HEADER_BYTES
        val frames = if (frameSize > 0 && payloadBytes % frameSize == 0) {
            payloadBytes / frameSize
        } else null
        return AudioPacket(
            frameId = u16(data, 0),
            frameSize = frameSize,
            chunkId = u8(data[4]),
            totalChunks = u8(data[5]),
            timestampMs = i64(data, 6),
            payloadBytes = payloadBytes,
            opusFramesInPayload = frames,
            payloadMatchesFrameSize = payloadBytes == frameSize
        )
    }

    fun envelopeFields(envelope: Envelope): Map<String, Any?> = mapOf(
        "message_type" to "0x${envelope.type.toString(16).padStart(2, '0')}",
        "declared_data_bytes" to envelope.declaredDataLength,
        "version" to envelope.version,
        "checksum" to "0x${envelope.checksum.toString(16).padStart(4, '0')}",
        "checksum_class" to envelope.checksumClassification,
        "crc_data" to "0x${envelope.crcData.toString(16).padStart(4, '0')}",
        "crc_header_data" to "0x${envelope.crcHeaderAndData.toString(16).padStart(4, '0')}",
        "crc_zeroed_envelope" to "0x${envelope.crcZeroedEnvelope.toString(16).padStart(4, '0')}",
        "trailing_bytes" to envelope.trailingBytes
    )

    fun audioFields(packet: AudioPacket): Map<String, Any?> = mapOf(
        "frame_id" to packet.frameId,
        "frame_size" to packet.frameSize,
        "chunk_id" to packet.chunkId,
        "total_chunks" to packet.totalChunks,
        "timestamp_ms" to packet.timestampMs,
        "payload_bytes" to packet.payloadBytes,
        "opus_frames_in_payload" to packet.opusFramesInPayload,
        "payload_matches_frame_size" to packet.payloadMatchesFrameSize
    )

    fun crc16Xmodem(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            crc = crc xor (u8(byte) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc
    }

    private fun parseRunningStatus(data: ByteArray): StatusMessage {
        if (data.isEmpty()) return invalid("running_status", data)
        val status = u8(data[0])
        val fields = linkedMapOf<String, Any?>(
            "status" to status,
            "status_name" to when (status) {
                0x00 -> "idle"
                0x01 -> "vibrating"
                0x02 -> "sleeping"
                0x03 -> "charging"
                0x04 -> "upgrading"
                0x05 -> "vibration_paused"
                0xFF -> "error"
                else -> "unknown"
            }
        )
        if ((status == 0x01 || status == 0x05) && data.size >= 5) {
            fields["duration_seconds"] = u16(data, 1)
            fields["remaining_seconds"] = u16(data, 3)
        }
        fields["payload_bytes"] = data.size
        return StatusMessage("running_status", fields)
    }

    private fun parseDeviceInfo(data: ByteArray): StatusMessage {
        if (data.size < 58) return invalid("device_info", data)
        return StatusMessage(
            "device_info",
            mapOf(
                "model" to data.asText(0, 16),
                "mac" to data.copyOfRange(16, 22).joinToString(":") { "%02X".format(u8(it)) },
                "firmware" to data.asText(22, 34),
                "hardware" to data.asText(34, 42),
                "serial" to data.asText(42, 58),
                "payload_bytes" to data.size
            )
        )
    }

    private fun parseSensorData(data: ByteArray): StatusMessage {
        if (data.size < 9) return invalid("sensor_data", data)
        return StatusMessage(
            "sensor_data",
            mapOf(
                "sensor_type" to u8(data[0]),
                "timestamp_ms" to i64(data, 1),
                "sensor_payload_bytes" to data.size - 9,
                "sensor_payload_hex" to data.copyOfRange(9, data.size).toBoundedHex()
            )
        )
    }

    private fun oneByte(
        name: String,
        field: String,
        data: ByteArray,
        transform: (Int) -> Any? = { it }
    ): StatusMessage = if (data.isNotEmpty()) {
        StatusMessage(name, mapOf(field to transform(u8(data[0])), "payload_bytes" to data.size))
    } else invalid(name, data)

    private fun invalid(name: String, data: ByteArray) = StatusMessage(
        name,
        mapOf("invalid" to true, "payload_bytes" to data.size, "payload_hex" to data.toBoundedHex())
    )

    private fun ByteArray.asText(start: Int, end: Int): String =
        copyOfRange(start, end).takeWhile { it.toInt() != 0 }.toByteArray()
            .toString(Charsets.UTF_8).trim()

    private fun ByteArray.toBoundedHex(limit: Int = 64): String {
        val shown = take(limit).joinToString(" ") { "%02X".format(u8(it)) }
        return if (size > limit) "$shown …(+${size - limit}B)" else shown
    }

    private fun u8(value: Byte): Int = value.toInt() and 0xFF

    private fun u16(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 1 >= bytes.size) return -1
        return u8(bytes[offset]) or (u8(bytes[offset + 1]) shl 8)
    }

    private fun i64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) {
            value = value or (u8(bytes[offset + index]).toLong() shl (index * 8))
        }
        return value
    }
}
