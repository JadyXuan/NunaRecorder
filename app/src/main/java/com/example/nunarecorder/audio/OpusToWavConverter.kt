package com.example.nunarecorder.audio

import android.util.Log
import org.concentus.OpusDecoder
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 将裸 Opus 流（无 Ogg 容器）解码为 WAV 文件。
 * 格式与 Python decode_opus.py 一致：16 kHz，双声道，80 字节/帧，20 ms/帧。
 */
object OpusToWavConverter {

    private const val SAMPLE_RATE = 16000
    private const val CHANNELS = 2
    private const val FRAME_SIZE_BYTES = 80
    private const val FRAME_DURATION_MS = 20
    /** 每帧每声道样本数（20ms @ 16kHz = 160） */
    private const val SAMPLES_PER_FRAME_PER_CHANNEL = 320
    /** 每帧总样本数（立体声） */
    private const val SAMPLES_PER_FRAME_TOTAL = SAMPLES_PER_FRAME_PER_CHANNEL * CHANNELS

    /**
     * 返回与 opus 文件同目录、同主名的 WAV 文件路径（不创建文件）。
     */
    fun getWavFileForOpus(opusFile: File): File {
        val name = opusFile.name
        val baseName = if (name.endsWith(".opus", ignoreCase = true)) {
            name.dropLast(5)
        } else {
            name
        }
        return File(opusFile.parent, "$baseName.wav")
    }

    /**
     * 若已存在对应 WAV 则直接返回，否则将 opus 解码为 WAV 并保存到同目录，返回 WAV 文件。
     * @param opusFile 裸 Opus 流文件
     * @param onProgress 可选进度回调 (currentFrame, totalFrames)
     * @return 对应的 WAV 文件
     * @throws Exception 解码或写入失败时抛出
     */
    @Throws(Exception::class)
    fun ensureWavFile(opusFile: File, onProgress: ((Int, Int) -> Unit)? = null): File {
        val wavFile = getWavFileForOpus(opusFile)
        if (wavFile.exists()) {
            Log.d("OpusToWav", "Using existing WAV: ${wavFile.absolutePath}, size=${wavFile.length()} bytes")
            return wavFile
        }
        Log.d("OpusToWav", "WAV not found, start decoding. opus=${opusFile.absolutePath}")
        decodeOpusToWav(opusFile, wavFile, onProgress)
        Log.d("OpusToWav", "Decoding finished. WAV=${wavFile.absolutePath}, size=${wavFile.length()} bytes")
        return wavFile
    }

    /**
     * 将裸 Opus 流解码并写入 WAV 文件。
     */
    @Throws(Exception::class)
    fun decodeOpusToWav(
        opusFile: File,
        wavFile: File,
        onProgress: ((Int, Int) -> Unit)? = null
    ) {
        val totalBytes = opusFile.length()
        val frameCount = (totalBytes / FRAME_SIZE_BYTES).toInt()
        if (frameCount == 0) {
            throw IllegalArgumentException("Opus file too short: $totalBytes bytes")
        }
        Log.d(
            "OpusToWav",
            "decodeOpusToWav: opus=${opusFile.name}, totalBytes=$totalBytes, frameCount=$frameCount"
        )

        val decoder = OpusDecoder(SAMPLE_RATE, CHANNELS)
        val frameBuffer = ByteArray(FRAME_SIZE_BYTES)
        val pcmBuffer = ShortArray(SAMPLES_PER_FRAME_TOTAL)

        val allPcm = mutableListOf<ByteArray>()
        FileInputStream(opusFile).use { input ->
            for (i in 0 until frameCount) {
                if (i % 50 == 0) {
                    Log.d("OpusToWav", "Decoding frame $i/$frameCount")
                }
                onProgress?.invoke(i, frameCount)
                val read = input.read(frameBuffer)
                if (read < FRAME_SIZE_BYTES) break
                val decoded = decoder.decode(
                    frameBuffer, 0, FRAME_SIZE_BYTES,
                    pcmBuffer, 0, SAMPLES_PER_FRAME_PER_CHANNEL,
                    false
                )
                if (decoded <= 0) {
                    Log.w("OpusToWav", "Frame $i: decoded <= 0 ($decoded), skip")
                    continue
                }
                val samplesToWrite = (decoded.toLong() * CHANNELS).toInt()
                val chunk = ByteArray(samplesToWrite * 2)
                ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).apply {
                    for (j in 0 until samplesToWrite) {
                        putShort(pcmBuffer[j])
                    }
                }
                allPcm.add(chunk)
            }
        }

        if (allPcm.isEmpty()) {
            Log.e("OpusToWav", "No PCM decoded, allPcm is empty")
            throw IllegalStateException("No PCM decoded")
        }

        val pcmBytes = allPcm.reduce { acc, bytes -> acc + bytes }
        val totalSamples = pcmBytes.size / 2
        Log.d(
            "OpusToWav",
            "Decoded PCM: totalPcmBytes=${pcmBytes.size}, totalSamples=$totalSamples"
        )
        writeWavFile(wavFile, pcmBytes, totalSamples)
    }

    /**
     * 将内存中的裸 Opus 流（与文件格式相同：80 字节/帧）解码为完整 WAV 字节（含 44 字节头），无文件 IO。
     * 用于 wearable 等场景：重组后的流已在 buffer 中，直接转录。
     * @return WAV 字节；若长度不足一帧或解码无有效 PCM 则返回 null
     */
    fun decodeOpusBytesToWavBytes(opusStream: ByteArray): ByteArray? {
        val totalBytes = opusStream.size
        val frameCount = (totalBytes / FRAME_SIZE_BYTES).toInt()
        if (frameCount == 0) return null

        val decoder = OpusDecoder(SAMPLE_RATE, CHANNELS)
        val frameBuffer = ByteArray(FRAME_SIZE_BYTES)
        val pcmBuffer = ShortArray(SAMPLES_PER_FRAME_TOTAL)
        val allPcm = mutableListOf<ByteArray>()

        var offset = 0
        for (i in 0 until frameCount) {
            System.arraycopy(opusStream, offset, frameBuffer, 0, FRAME_SIZE_BYTES)
            offset += FRAME_SIZE_BYTES
            val decoded = decoder.decode(
                frameBuffer, 0, FRAME_SIZE_BYTES,
                pcmBuffer, 0, SAMPLES_PER_FRAME_PER_CHANNEL,
                false
            )
            if (decoded <= 0) continue
            val samplesToWrite = (decoded.toLong() * CHANNELS).toInt()
            val chunk = ByteArray(samplesToWrite * 2)
            ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).apply {
                for (j in 0 until samplesToWrite) {
                    putShort(pcmBuffer[j])
                }
            }
            allPcm.add(chunk)
        }
        if (allPcm.isEmpty()) return null
        val pcmBytes = allPcm.reduce { acc, bytes -> acc + bytes }
        return buildWavBytes(pcmBytes)
    }

    /**
     * 与 writeWavFile 相同头格式，写入字节数组而非文件。
     */
    fun buildWavBytes(pcmBytes: ByteArray): ByteArray {
        val dataSize = pcmBytes.size
        val fileSize = 36 + dataSize
        val out = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray())
        out.putInt(fileSize)
        out.put("WAVE".toByteArray())
        out.put("fmt ".toByteArray())
        out.putInt(16)
        out.putShort(1)
        out.putShort(CHANNELS.toShort())
        out.putInt(SAMPLE_RATE)
        out.putInt(SAMPLE_RATE * CHANNELS * 2)
        out.putShort((CHANNELS * 2).toShort())
        out.putShort(16)
        out.put("data".toByteArray())
        out.putInt(dataSize)
        out.put(pcmBytes)
        return out.array()
    }

    private fun writeWavFile(wavFile: File, pcmBytes: ByteArray, totalSamples: Int) {
        val dataSize = pcmBytes.size
        val fileSize = 36 + dataSize
        RandomAccessFile(wavFile, "rw").use { out ->
            out.write("RIFF".toByteArray())
            out.write(intToLittleEndian(fileSize))
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.write(intToLittleEndian(16)) // chunk size
            out.write(shortToLittleEndian(1)) // PCM
            out.write(shortToLittleEndian(CHANNELS.toShort()))
            out.write(intToLittleEndian(SAMPLE_RATE))
            out.write(intToLittleEndian(SAMPLE_RATE * CHANNELS * 2)) // byte rate
            out.write(shortToLittleEndian((CHANNELS * 2).toShort())) // block align
            out.write(shortToLittleEndian(16)) // bits per sample
            out.write("data".toByteArray())
            out.write(intToLittleEndian(dataSize))
            out.write(pcmBytes)
        }
        Log.d(
            "OpusToWav",
            "WAV written: path=${wavFile.absolutePath}, dataSize=$dataSize, fileSize=$fileSize, fileLen=${wavFile.length()}, totalSamples=$totalSamples"
        )
    }

    private fun intToLittleEndian(v: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    }

    private fun shortToLittleEndian(v: Short): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array()
    }
}
