package com.example.nunarecorder.audio

import android.media.MediaPlayer
import android.util.Log
import com.example.nunarecorder.audio.OpusToWavConverter.ensureWavFile
import com.example.nunarecorder.audio.OpusToWavConverter.getWavFileForOpus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SegmentPlaybackState(
    val sessionDirPath: String,
    val segmentIndex: Int,
    val segmentLabel: String,
    val converting: Boolean = false
)

/**
 * 会话内单段 Opus 播放：按需转 WAV 后用 MediaPlayer 播放。
 */
class SegmentAudioPlayer(
    private val onLog: (String) -> Unit
) {
    private companion object {
        private const val TAG = "SegmentAudioPlayer"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var onStateChanged: ((SegmentPlaybackState?) -> Unit)? = null

    var state: SegmentPlaybackState? = null
        private set

    fun setOnStateChanged(listener: (SegmentPlaybackState?) -> Unit) {
        onStateChanged = listener
    }

    private fun emit(newState: SegmentPlaybackState?) {
        state = newState
        onStateChanged?.invoke(newState)
    }

    fun stop() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        emit(null)
    }

    fun play(
        scope: CoroutineScope,
        sessionDir: File,
        segmentIndex: Int,
        opusFile: File
    ) {
        if (!opusFile.isFile || opusFile.length() == 0L) {
            onLog("音频段不存在或为空: ${opusFile.name}")
            return
        }
        val sessionPath = sessionDir.absolutePath
        val label = opusFile.name
        stop()
        emit(SegmentPlaybackState(sessionPath, segmentIndex, label, converting = false))

        val wavFile = getWavFileForOpus(opusFile)
        if (wavFile.exists() && wavFile.length() > 0L) {
            startWav(wavFile, sessionPath, segmentIndex, label)
            return
        }

        emit(SegmentPlaybackState(sessionPath, segmentIndex, label, converting = true))
        scope.launch(Dispatchers.IO) {
            try {
                ensureWavFile(opusFile)
                withContext(Dispatchers.Main) {
                    val s = state
                    if (s?.sessionDirPath == sessionPath && s.segmentIndex == segmentIndex) {
                        startWav(getWavFileForOpus(opusFile), sessionPath, segmentIndex, label)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Opus to WAV failed", e)
                withContext(Dispatchers.Main) {
                    onLog("转码失败: ${e.message}")
                    val s = state
                    if (s?.sessionDirPath == sessionPath && s.segmentIndex == segmentIndex) {
                        emit(null)
                    }
                }
            }
        }
    }

    private fun startWav(
        wavFile: File,
        sessionPath: String,
        segmentIndex: Int,
        label: String
    ) {
        emit(SegmentPlaybackState(sessionPath, segmentIndex, label, converting = false))
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(wavFile.absolutePath)
                setOnPreparedListener {
                    onLog("播放段 $segmentIndex (${wavFile.name})")
                    start()
                }
                setOnCompletionListener {
                    stop()
                }
                setOnErrorListener { _, what, extra ->
                    onLog("播放错误: what=$what extra=$extra")
                    stop()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "startWav failed", e)
            onLog("播放失败: ${e.message}")
            stop()
        }
    }
}
