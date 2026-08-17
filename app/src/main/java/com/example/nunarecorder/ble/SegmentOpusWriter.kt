package com.example.nunarecorder.ble

import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 把已经拼齐的 Opus 帧顺序写进一个分段文件。
 *
 * 只负责写。重组、丢帧记账在 [OpusStreamAssembler]，分段轮转在
 * [com.example.nunarecorder.recording.SessionRecorder]。
 *
 * 取代了原来的 `BleAudioReassembler`：那个类把「解析 BLE 字节流」和「写文件」揉在一起，
 * 于是它必须随分段一起重建，跨段的半条消息和未凑齐的帧每分钟都被丢一次，
 * 而且 stream 层的丢帧无处上报。
 *
 * 不做缓冲：`FileOutputStream` 直接落到 page cache，进程被杀时已写入的音频不会丢。
 * 50 帧/秒 × 80 字节的写入量对 syscall 来说可以忽略。
 */
class SegmentOpusWriter(
    val file: File,
    private val onWriteFailure: (String) -> Unit = {}
) {

    companion object {
        private const val TAG = "SegmentOpusWriter"
    }

    private var out: FileOutputStream? = null
    private var failureReported = false

    init {
        out = try {
            FileOutputStream(file)
        } catch (e: Exception) {
            reportFailure("open failed: ${file.name}", e)
            null
        }
    }

    var bytesWritten: Long = 0L
        private set

    fun write(opus: ByteArray): Boolean {
        val stream = out ?: run {
            reportFailure("write skipped: ${file.name}", null)
            return false
        }
        try {
            stream.write(opus)
            bytesWritten += opus.size
            return true
        } catch (e: Exception) {
            out = null
            reportFailure("write failed: ${file.name}", e)
            return false
        }
    }

    fun close() {
        try {
            out?.flush()
            out?.close()
        } catch (e: Exception) {
            reportFailure("close failed: ${file.name}", e)
        } finally {
            out = null
        }
    }

    private fun reportFailure(message: String, error: Exception?) {
        if (failureReported) return
        failureReported = true
        if (error == null) Log.e(TAG, message) else Log.e(TAG, message, error)
        onWriteFailure(message)
    }
}
