package com.example.nunarecorder.recording

import android.content.Context
import android.content.Intent
import com.example.nunarecorder.service.RecordingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI 与 [RecordingService] 之间的唯一接口。
 *
 * Activity 不再持有 BLE 或录制管线——那正是原来的问题：`MainActivity` 的
 * `private val sessionRecorder = SessionRecorder { ... }` 是 Activity 字段，
 * 进程被杀、Activity 被回收、划掉任务卡片，采集都会一起没。
 * 现在 Activity 只读这里的两个 StateFlow，写入方只有服务。
 */
object RecordingController {

    private val _link = MutableStateFlow(LinkStatus())
    val link: StateFlow<LinkStatus> = _link.asStateFlow()

    private val _stats = MutableStateFlow<LiveRecordingStats?>(null)
    val stats: StateFlow<LiveRecordingStats?> = _stats.asStateFlow()

    internal fun publishLink(status: LinkStatus) {
        _link.value = status
    }

    internal fun publishStats(stats: LiveRecordingStats?) {
        _stats.value = stats
    }

    fun start(context: Context, deviceName: String, deviceAddress: String) {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_DEVICE_NAME, deviceName)
            putExtra(RecordingService.EXTRA_DEVICE_ADDRESS, deviceAddress)
        }
        context.startForegroundService(intent)
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, RecordingService::class.java).apply {
                action = RecordingService.ACTION_STOP
            }
        )
    }
}
