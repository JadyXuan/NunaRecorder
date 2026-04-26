package com.example.nunarecorder.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.nunarecorder.ui.MainViewModel

/**
 * 临时调试验证页：WearableRecordingController + AudioChunkProvider。
 * 整页可注释/删除；日志仅写入 [MainViewModel.wearableDebugLogLines]，与主 Log 分离。
 */
// DEBUG_WEARABLE_START
@Composable
fun WearableDebugScreen(
    viewModel: MainViewModel,
    deviceAddress: String,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onQueryIsRecording: () -> Unit,
    onSetListenerAndStartChunk: () -> Unit,
    onStopChunkDelivery: () -> Unit,
    onStartTranscription: () -> Unit,
    onStopTranscription: () -> Unit,
    onClearLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Wearable debug (TAG=WearableDebug). Device: $deviceAddress")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStartRecording) { Text("startRecording") }
            Button(onClick = onStopRecording) { Text("stopRecording") }
            Button(onClick = onQueryIsRecording) { Text("isRecording") }
        }
        Text("Chunk 10s / overlap 2s; overlap_*.wav only if dump ON (debug enables dump)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSetListenerAndStartChunk) { Text("setListener + startChunkDelivery") }
            Button(onClick = onStopChunkDelivery) { Text("stopChunkDelivery") }
            Button(onClick = onClearLog) { Text("Clear log") }
        }
        Text("转写 (Deepgram listen-flux)：需先 startRecording，并在 WearableConnectionConfig 中配置 Deepgram API Key；结果会打在下方日志和 Logcat WearableDebug。")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onStartTranscription) {
                Text("Start 转写")
            }
            Button(onClick = onStopTranscription) {
                Text("Stop 转写")
            }
        }
        Text("Wearable-only log (not NunaRecorder appendLog):")
        // 读取 mutableStateListOf 以订阅变化（append 须在主线程）
        val logText = viewModel.wearableDebugLogLines.joinToString("\n")
        OutlinedTextField(
            value = logText,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            maxLines = Int.MAX_VALUE,
            minLines = 12
        )
    }
}
// DEBUG_WEARABLE_END
