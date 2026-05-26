package com.example.nunarecorder.migration

import android.content.Context
import com.example.nunarecorder.service.MigrationService
import com.example.nunarecorder.util.BatteryOptimizationHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 迁移任务状态（供 UI 订阅）；实际工作在 [MigrationService] 前台服务中执行。
 */
object MigrationCoordinator {

    enum class Phase {
        SPLITTING,
        VAD_ONLY,
        DONE,
        ERROR
    }

    data class State(
        val opusPath: String,
        val displayName: String,
        val phase: Phase,
        val progress: Float,
        val message: String
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    var onLog: ((String) -> Unit)? = null

    fun isActiveFor(opusPath: String): Boolean {
        val s = _state.value ?: return false
        return s.opusPath == opusPath && s.phase !in setOf(Phase.DONE, Phase.ERROR)
    }

    fun start(context: Context, legacyOpus: File) {
        val path = legacyOpus.absolutePath
        if (isActiveFor(path)) {
            log("迁移已在进行中: ${legacyOpus.name}")
            return
        }
        _state.value = State(
            opusPath = path,
            displayName = legacyOpus.name,
            phase = Phase.SPLITTING,
            progress = 0f,
            message = "准备迁移…"
        )
        val app = context.applicationContext
        if (!BatteryOptimizationHelper.isIgnoringOptimizations(app)) {
            log("提示：请在下一步允许「忽略电池优化」，否则息屏可能中断迁移")
            BatteryOptimizationHelper.requestIgnoreOptimizations(context)
        }
        MigrationService.enqueue(app, path)
    }

    internal fun updateProgress(
        opusPath: String,
        displayName: String,
        phase: Phase,
        progress: Float,
        message: String
    ) {
        _state.value = State(opusPath, displayName, phase, progress.coerceIn(0f, 1f), message)
    }

    internal fun complete(opusPath: String, displayName: String, message: String) {
        log(message)
        _state.value = State(opusPath, displayName, Phase.DONE, 1f, message)
    }

    internal fun fail(opusPath: String, displayName: String, message: String) {
        log(message)
        _state.value = State(opusPath, displayName, Phase.ERROR, 0f, message)
    }

    internal fun log(msg: String) {
        onLog?.invoke(msg)
    }

    fun clearDoneState() {
        val s = _state.value ?: return
        if (s.phase == Phase.DONE || s.phase == Phase.ERROR) {
            _state.value = null
        }
    }
}
