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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object LifelogCoordinator {
    // Object properties are initialized top-to-bottom. Keep UTC before _state because
    // the initial state calls today(), which formats its date with this time zone.
    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

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
            baseUrl = settings.apiBaseUrl(),
            userId = settings.userId,
            basicAuthUsername = settings.basicAuthUsername,
            basicAuthPassword = settings.basicAuthPassword
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
                val payloads = withContext(Dispatchers.IO) {
                    Triple(client.timeline(date), client.pending(), client.diary(date))
                }
                val timeline = payloads.first
                val pending = payloads.second
                val diary = payloads.third
                _state.value = LifelogUiState(
                    date = date,
                    loading = false,
                    timeline = timeline.entries,
                    pending = pending.prompts,
                    diary = diary.entries,
                    taxonomy = timeline.taxonomy,
                    schemaVersion = maxOf(
                        timeline.schemaVersion,
                        pending.schemaVersion,
                        diary.schemaVersion
                    ),
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
                        else -> "活动标签已更新: ${
                            result.effectiveLabel ?: prompt.suggestedName
                        }"
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
        val format = utcDateFormat()
        val current = format.parse(date) ?: Date()
        val calendar = Calendar.getInstance(UTC).apply {
            time = current
            add(Calendar.DAY_OF_MONTH, days)
        }
        return format.format(calendar.time)
    }

    private fun today(): String =
        utcDateFormat().format(Date())

    private fun utcDateFormat() =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = UTC }

}
