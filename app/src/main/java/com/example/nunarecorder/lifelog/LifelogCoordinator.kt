package com.example.nunarecorder.lifelog

import com.example.nunarecorder.data.UserSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LifelogCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(LifelogUiState(today()))
    val state: StateFlow<LifelogUiState> = _state.asStateFlow()

    private var api: LifelogApiClient? = null
    private var enabled: Boolean = true
    var onLog: ((String) -> Unit)? = null

    fun configure(httpClient: OkHttpClient, settings: UserSettings) {
        enabled = settings.lifelogEnabled
        api = LifelogApiClient(
            httpClient = httpClient,
            baseUrl = "http://${settings.serverHost}:${settings.serverPort}",
            userId = settings.userId
        )
    }

    fun refresh(date: String = _state.value.date) {
        if (!enabled) {
            _state.value = _state.value.copy(
                loading = false,
                error = "生活记录功能已在设置中关闭"
            )
            return
        }
        val client = api ?: return
        if (_state.value.loading) return
        _state.value = _state.value.copy(date = date, loading = true, error = null)
        scope.launch {
            try {
                val pair = withContext(Dispatchers.IO) {
                    client.timeline(date) to client.pending()
                }
                val timeline = pair.first
                val pending = pair.second
                _state.value = LifelogUiState(
                    date = date,
                    loading = false,
                    timeline = timeline.entries,
                    pending = pending.prompts,
                    taxonomy = when {
                        pending.taxonomy.isNotEmpty() -> pending.taxonomy
                        else -> timeline.taxonomy
                    },
                    schemaVersion = maxOf(timeline.schemaVersion, pending.schemaVersion),
                    lastUpdatedMs = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                onLog?.invoke("生活记录刷新失败: ${e.message}")
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "服务器请求失败"
                )
            }
        }
    }

    fun annotate(
        prompt: AnnotationPrompt,
        action: String,
        label: String? = null
    ) {
        val client = api ?: return
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, error = null)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    client.annotate(prompt.eventId, action, label)
                }
                onLog?.invoke(
                    when (action) {
                        "skip" -> "已跳过本次标注"
                        else -> "活动标签已更新: ${result.label ?: prompt.suggestedName}"
                    }
                )
                _state.value = _state.value.copy(loading = false)
                refresh(_state.value.date)
            } catch (e: Exception) {
                onLog?.invoke("提交标注失败: ${e.message}")
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "提交失败"
                )
            }
        }
    }

    fun previousDay() = refresh(shiftDate(_state.value.date, -1))

    fun nextDay() {
        val next = shiftDate(_state.value.date, 1)
        if (next <= today()) refresh(next)
    }

    private fun shiftDate(date: String, days: Int): String {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val current = format.parse(date) ?: Date()
        return format.format(Date(current.time + days * 86_400_000L))
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
