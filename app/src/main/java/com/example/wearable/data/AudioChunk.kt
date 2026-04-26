package com.example.wearable.data

/**
 * 单段原始音频数据块。
 * 与 com.example.nunapin.data.AudioChunk 结构一致，便于接入方转换。
 *
 * @param data 本段的原始音频字节（完整 WAV，含 44 字节头）
 * @param startTimeMs 本段开始的墙钟时间（毫秒，epoch）
 * @param endTimeMs 本段结束的墙钟时间（毫秒，epoch）
 */
data class AudioChunk(
    val data: ByteArray,
    val startTimeMs: Long,
    val endTimeMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioChunk
        if (!data.contentEquals(other.data)) return false
        if (startTimeMs != other.startTimeMs) return false
        if (endTimeMs != other.endTimeMs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + startTimeMs.hashCode()
        result = 31 * result + endTimeMs.hashCode()
        return result
    }
}
