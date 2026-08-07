package com.example.nunarecorder.voiceprint

import com.example.nunarecorder.audio.OpusToPcmMono
import com.example.nunarecorder.ble.OpusStreamAssembler
import java.io.File

/**
 * 从**已连接的 Nuna 设备**录一段声纹。
 *
 * 用设备而不是手机麦克风，是因为声纹要和采集音频比对——换个麦克风声学特征就对不上了。
 * 走 App + 已连接设备的好处是这条规矩**自动成立**，不需要任何人记得。
 *
 * 复用采集主路径的 [OpusStreamAssembler]（同一份重组和丢帧逻辑），
 * 但写到独立文件，不碰会话目录。
 */
class VoiceprintCapture(private val outputFile: File) {

    private val assembler = OpusStreamAssembler()
    private val opus = java.io.ByteArrayOutputStream(256 * 1024)

    @Volatile
    var packets: Int = 0
        private set

    val durationMs: Long get() = packets * OpusStreamAssembler.FRAME_DURATION_MS

    /** 喂入一个 A003 notification 的原始字节。 */
    @Synchronized
    fun feed(data: ByteArray) {
        for (frame in assembler.feed(data)) {
            opus.write(frame.opus)
            packets += frame.opus.size / OpusStreamAssembler.OPUS_FRAME_SIZE
        }
    }

    /** 落盘并做质量判定。返回判定结果，文件保留（不合格也留着，便于排查）。 */
    @Synchronized
    fun finish(): VoiceprintQuality.Result {
        val bytes = opus.toByteArray()
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(bytes)

        // 复用采集主路径的解码器，不另写一份——两份解码迟早会漂开，
        // 而声纹和采集音频必须是同一个量纲才能比对。
        val pcm = runCatching { OpusToPcmMono.decodeFileToMonoFloat(outputFile) }
            .getOrDefault(FloatArray(0))
        return VoiceprintQuality.evaluate(pcm)
    }

    @Synchronized
    fun discard() {
        opus.reset()
        packets = 0
        assembler.reset()
        runCatching { outputFile.delete() }
    }
}
