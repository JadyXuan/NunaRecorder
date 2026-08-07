package com.example.nunarecorder.voiceprint

import android.content.Context
import com.example.nunarecorder.util.DiagnosticsLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 声纹录制的全局状态，供服务喂数据、界面读进度。
 *
 * 做成单例是因为音频来自 [com.example.nunarecorder.service.RecordingService] 的 BLE
 * 回调，而向导在 Activity 里——两者没有直接引用关系，中间必须有个共享点。
 */
object VoiceprintSession {

    enum class Step { READ, FREE }

    data class State(
        val active: Boolean = false,
        val step: Step = Step.READ,
        val elapsedMs: Long = 0L,
        val lastResult: VoiceprintQuality.Result? = null,
        val readDone: Boolean = false,
        val freeDone: Boolean = false
    ) {
        val allDone: Boolean get() = readDone && freeDone
    }

    /** 朗读文本全体参与者相同，便于互相比较 */
    const val READ_SCRIPT =
        "我叫参与者，今天天气不错。我平时会在图书馆看书，也常常在食堂和朋友聊天。" +
            "早上出门的时候会经过一条种满树的路，傍晚回来时那里很安静。" +
            "有时候我会听音乐，有时候什么也不做，就看看窗外。"

    const val FREE_PROMPT = "请用自己的话讲讲：你平时一天是怎么过的？"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var capture: VoiceprintCapture? = null

    fun fileFor(context: Context, step: Step): File =
        File(context.filesDir, "enrollment/voiceprint_${step.name.lowercase()}.opus")

    fun start(context: Context, step: Step) {
        capture?.discard()
        capture = VoiceprintCapture(fileFor(context, step))
        _state.value = _state.value.copy(active = true, step = step, elapsedMs = 0L, lastResult = null)
        DiagnosticsLog.log("Voiceprint", "开始录制 ${step.name}")
    }

    /** 由服务在 BLE 回调线程调用；没在录制时是一次 volatile 读，可忽略。 */
    fun feed(data: ByteArray) {
        val c = capture ?: return
        c.feed(data)
        val s = _state.value
        if (s.active) _state.value = s.copy(elapsedMs = c.durationMs)
    }

    /** 停止并判定。不合格也保留文件，便于排查。 */
    fun stop(): VoiceprintQuality.Result? {
        val c = capture ?: return null
        val result = c.finish()
        val s = _state.value
        _state.value = s.copy(
            active = false,
            lastResult = result,
            readDone = if (s.step == Step.READ) result.ok else s.readDone,
            freeDone = if (s.step == Step.FREE) result.ok else s.freeDone
        )
        DiagnosticsLog.log(
            "Voiceprint",
            "${s.step.name} 判定 ${result.verdict}：${result.advice}"
        )
        capture = null
        return result
    }

    fun cancel() {
        capture?.discard()
        capture = null
        _state.value = _state.value.copy(active = false, elapsedMs = 0L)
    }

    fun reset() {
        cancel()
        _state.value = State()
    }
}
