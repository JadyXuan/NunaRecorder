package com.example.nunarecorder.ble

import android.util.Log
import java.io.File
import java.io.FileOutputStream

class BleAudioReassembler(
    private val outputFile: File,
    private val onLog: (String) -> Unit = {}
) {

    companion object {
        private const val TAG = "BleAudioReassembler"

        private const val HEADER_MAGIC: Int = 0xAA
        private const val AUDIO_TYPE: Int = 0x10   // 对应 MessageType.AUDIO_RECORDING_DATA

        private const val MIN_HEADER_LEN = 7       // AA + type + len(2) + ver + checksum(2)
        private const val MIN_AUDIO_BIZ_HEADER = 14
        private const val OPUS_FRAME_SIZE = 80
    }

    private var fos: FileOutputStream? = null

    // frameId -> FrameInfo
    private val frames = mutableMapOf<Int, FrameInfo>()

    // 统计
    private var msgCount = 0
    private var audioMsgCount = 0
    private var validFrameCount = 0
    private var opusBytesWritten = 0L
    private var expectedFrameId: Int? = null
    private var integrityIssue: String? = null
    val integrityOk: Boolean get() = integrityIssue == null

    private data class FrameInfo(
        val frameId: Int,
        var frameSize: Int,
        var totalChunks: Int,
        var timestamp: Long,
        val chunks: MutableMap<Int, ByteArray>
    )

    init {
        fos = FileOutputStream(outputFile)
        onLog("BleAudioReassembler: output=${outputFile.absolutePath}")
    }

    fun close(): String? {
        try {
            if (frames.isNotEmpty() && integrityIssue == null) {
                markIntegrityError("${frames.size} 个 BLE 音频帧未收齐")
            }
            fos?.flush()
            fos?.close()
            fos = null
            onLog(
                "BleAudioReassembler closed. totalFrames=$validFrameCount, " +
                    "opusBytes=$opusBytesWritten, integrity=${integrityIssue ?: "ok"}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error closing reassembler", e)
            markIntegrityError("写入文件失败: ${e.message}")
        }
        return integrityIssue
    }

    /**
     * 将从 BLE 收到的一段“原始 payload”喂给重组器。
     * 注意：这里假设你现在写入文件的 data 就是“完整 BLE 消息流中的一段”，
     * 如果设备每个 notification 就是完整一条 message，那么这里可以直接解析；
     * 如果可能被拆包/合包，需要在上层先做 buffer 处理。
     */
    fun feed(data: ByteArray) {
        // 这里直接按 Python 那样，从头到尾扫描多条消息
        var idx = 0
        val totalLen = data.size

        while (idx + MIN_HEADER_LEN <= totalLen) {
            if ((data[idx].toInt() and 0xFF) != HEADER_MAGIC) {
                idx++
                continue
            }

            if (idx + MIN_HEADER_LEN > totalLen) break

            val msgType = data[idx + 1].toInt() and 0xFF
            val length = readLeU16(data, idx + 2)
            val msgTotalLen = MIN_HEADER_LEN + length

            if (idx + msgTotalLen > totalLen) {
                markIntegrityError(
                    "BLE notification 被截断：需要 $msgTotalLen B，仅收到 ${totalLen - idx} B"
                )
                break
            }

            val payload = data.copyOfRange(idx + MIN_HEADER_LEN, idx + msgTotalLen)

            msgCount++

            if (msgType == AUDIO_TYPE) {
                audioMsgCount++
                parseAudioPayload(payload)
            }

            idx += msgTotalLen
        }
    }

    private fun parseAudioPayload(payload: ByteArray) {
        if (payload.size < MIN_AUDIO_BIZ_HEADER) {
            onLog("[reassembler] audio payload too short, len=${payload.size}, skip")
            return
        }

        val frameId = readLeU16(payload, 0)
        val frameSize = readLeU16(payload, 2)
        val chunkId = payload[4].toInt() and 0xFF
        val totalChunks = payload[5].toInt() and 0xFF
        val timestamp = readLeU64(payload, 6)
        val opusData = payload.copyOfRange(14, payload.size)

        if (frameSize != OPUS_FRAME_SIZE) {
            markIntegrityError("frameId=$frameId 长度=$frameSize，期望 $OPUS_FRAME_SIZE")
            return
        }
        if (totalChunks <= 0 || chunkId >= totalChunks) {
            markIntegrityError(
                "frameId=$frameId 分片编号异常 chunk=$chunkId/$totalChunks"
            )
            return
        }

        val frame = frames.getOrPut(frameId) {
            FrameInfo(
                frameId = frameId,
                frameSize = frameSize,
                totalChunks = totalChunks,
                timestamp = timestamp,
                chunks = mutableMapOf()
            )
        }

        // 更新 meta（防止前面的 chunk 先到）
        frame.frameSize = frameSize
        frame.totalChunks = maxOf(frame.totalChunks, totalChunks)
        frame.timestamp = timestamp

        frame.chunks[chunkId] = opusData

        // 如果收齐了所有 chunk，可以直接写出这个 frame
        if (frame.chunks.size == frame.totalChunks) {
            // 你可以在这里选择“按 timestamp 排序后再写”，
            // 或者简单按 frameId/到达顺序写。Python 是最后统一排序。
            writeFrameToFile(frame)
            frames.remove(frameId)
        }
    }

    private fun writeFrameToFile(frame: FrameInfo) {
        val currentExpected = expectedFrameId
        if (currentExpected != null && frameIdDistance(currentExpected, frame.frameId) != 0) {
            markIntegrityError(
                "BLE 音频帧不连续：期望 $currentExpected，收到 ${frame.frameId}"
            )
        }
        expectedFrameId = (frame.frameId + 1) and 0xFFFF

        val concatenated = ByteArray(frame.chunks.values.sumOf { it.size })
        var pos = 0
        for (cid in 0 until frame.totalChunks) {
            val chunk = frame.chunks[cid]
            if (chunk == null) {
                onLog("[reassembler] frame missing chunk $cid, skip")
                return
            }
            System.arraycopy(chunk, 0, concatenated, pos, chunk.size)
            pos += chunk.size
        }

        if (concatenated.size != frame.frameSize) {
            markIntegrityError(
                "frameId=${frame.frameId} 重组后 ${concatenated.size} B，声明 ${frame.frameSize} B"
            )
            return
        }

        try {
            fos?.write(concatenated)
            fos?.flush()
            validFrameCount++
            opusBytesWritten += concatenated.size
        } catch (e: Exception) {
            Log.e(TAG, "Error writing opus frame", e)
            markIntegrityError("写入文件失败: ${e.message}")
        }
    }

    private fun frameIdDistance(expected: Int, actual: Int): Int =
        (actual - expected) and 0xFFFF

    private fun markIntegrityError(message: String) {
        if (integrityIssue == null) {
            integrityIssue = message
            onLog("[reassembler] 完整性错误：$message")
        }
    }

    private fun readLeU16(b: ByteArray, offset: Int): Int {
        return (b[offset].toInt() and 0xFF) or
                ((b[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readLeU64(b: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = v or (((b[offset + i].toInt() and 0xFF).toLong()) shl (8 * i))
        }
        return v
    }
}
