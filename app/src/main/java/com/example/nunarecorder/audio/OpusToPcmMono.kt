package com.example.nunarecorder.audio

import org.concentus.OpusDecoder
import java.io.File
import java.io.FileInputStream

/**
 * 将裸 Opus 流解码为 16 kHz 单声道 float PCM（-1..1），供 Silero VAD 使用。
 */
object OpusToPcmMono {

    private const val SAMPLE_RATE = 16000
    private const val CHANNELS = 2
    private const val FRAME_SIZE_BYTES = 80
    private const val SAMPLES_PER_FRAME_PER_CHANNEL = 320
    private const val SAMPLES_PER_FRAME_TOTAL = SAMPLES_PER_FRAME_PER_CHANNEL * CHANNELS

    fun decodeFileToMonoFloat(opusFile: File): FloatArray {
        val totalBytes = opusFile.length()
        val frameCount = (totalBytes / FRAME_SIZE_BYTES).toInt()
        if (frameCount == 0) return FloatArray(0)

        val decoder = OpusDecoder(SAMPLE_RATE, CHANNELS)
        val frameBuffer = ByteArray(FRAME_SIZE_BYTES)
        val pcmBuffer = ShortArray(SAMPLES_PER_FRAME_TOTAL)
        val mono = ArrayList<Float>(frameCount * SAMPLES_PER_FRAME_PER_CHANNEL)

        FileInputStream(opusFile).use { input ->
            for (i in 0 until frameCount) {
                val read = input.read(frameBuffer)
                if (read < FRAME_SIZE_BYTES) break
                val decoded = try {
                    decoder.decode(
                        frameBuffer, 0, FRAME_SIZE_BYTES,
                        pcmBuffer, 0, SAMPLES_PER_FRAME_PER_CHANNEL,
                        false
                    )
                } catch (e: Exception) {
                    try {
                        decoder.decode(null, 0, 0, pcmBuffer, 0, SAMPLES_PER_FRAME_PER_CHANNEL, false)
                    } catch (e2: Exception) { 0 }
                }
                if (decoded <= 0) continue
                for (ch in 0 until decoded) {
                    val left = pcmBuffer[ch * 2]
                    val right = pcmBuffer[ch * 2 + 1]
                    val mixed = ((left.toInt() + right.toInt()) / 2).toShort()
                    mono.add(mixed / 32768f)
                }
            }
        }
        return mono.toFloatArray()
    }
}
