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
        /** lastResult 属于哪一段；界面要把结论显示在对应的卡片里，不是页面底部 */
        val lastStep: Step? = null,
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

    const val FREE_PROMPT =
        "请用自己的话讲讲：你平时一天是怎么过的？从起床开始，去了哪些地方、" +
            "做了什么、和谁在一起、什么时候最放松、什么时候最累。" +
            "讲得越具体越好——这段话既用来认出你的声音，本身也是有价值的背景材料。"

    /** 每段的最低时长；上不封顶 */
    fun minDurationFor(step: Step): Long = when (step) {
        Step.READ -> VoiceprintQuality.MIN_READ_MS
        Step.FREE -> VoiceprintQuality.MIN_FREE_MS
    }

    fun suggestedDurationFor(step: Step): Long = when (step) {
        Step.READ -> VoiceprintQuality.SUGGESTED_READ_MS
        Step.FREE -> VoiceprintQuality.SUGGESTED_FREE_MS
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var capture: VoiceprintCapture? = null

    fun fileFor(context: Context, step: Step): File =
        File(context.filesDir, "enrollment/voiceprint_${step.name.lowercase()}.opus")

    /**
     * 声纹录制期间**正常采集必须暂停**。
     *
     * 用户 2026-08-09 质疑得对：两段声纹用的是同一条 BLE 音频流，如果同时喂给
     * SessionRecorder，朗读文本和"讲讲你的一天"就会原样进入待标注数据集。
     * 那两段是**入组材料**（属于参与者档案），不是当天的生活记录；
     * 让它们混进标注池既污染数据集，又等于把一段明确为登记目的录的音
     * 拿去做别的用途。
     *
     * 这个标记由 RecordingService 在音频分发处读：为 true 时只喂声纹，不写会话。
     */
    val isCapturing: Boolean get() = _state.value.active

    fun start(context: Context, step: Step) {
        capture?.discard()
        capture = VoiceprintCapture(fileFor(context, step))
        _state.value = _state.value.copy(active = true, step = step, elapsedMs = 0L, lastResult = null, lastStep = null)
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
        val result = c.finish(minDurationFor(_state.value.step))
        val s = _state.value
        _state.value = s.copy(
            active = false,
            lastResult = result,
            lastStep = s.step,
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
