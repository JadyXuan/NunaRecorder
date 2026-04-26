package com.example.nunarecorder.ble

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    }

    private var fos: FileOutputStream? = null

    // frameId -> FrameInfo
    private val frames = mutableMapOf<Int, FrameInfo>()

    // 统计
    private var msgCount = 0
    private var audioMsgCount = 0
    private var validFrameCount = 0
    private var opusBytesWritten = 0L

    private data class FrameInfo(
        var frameSize: Int,
        var totalChunks: Int,
        var timestamp: Long,
        val chunks: MutableMap<Int, ByteArray>
    )

    init {
        fos = FileOutputStream(outputFile)
        onLog("BleAudioReassembler: output=${outputFile.absolutePath}")
    }

    fun close() {
        try {
            fos?.flush()
            fos?.close()
            fos = null
            onLog("BleAudioReassembler closed. totalFrames=$validFrameCount, opusBytes=$opusBytesWritten")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing reassembler", e)
        }
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
                // 当前 buffer 不够一条完整消息，下次再解析
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

        val frame = frames.getOrPut(frameId) {
            FrameInfo(
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

        try {
            fos?.write(concatenated)
            fos?.flush()
            validFrameCount++
            opusBytesWritten += concatenated.size
        } catch (e: Exception) {
            Log.e(TAG, "Error writing opus frame", e)
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
