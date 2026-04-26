package com.example.nunarecorder.ui.screen

import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nunarecorder.ui.components.RecordingItem
import java.io.File

private const val CONTEXT_DATA_SUFFIX = ".bin"

// Represents what action triggered the multimodal dialog
private enum class MultiModalAction { SHARE, UPLOAD }

@Composable
fun RecordingsScreen(
    onPlayFile: (File, (Int, Int) -> Unit) -> Unit,
    onShareFile: (File) -> Unit,
    onShareFileWithContext: (File) -> Unit,
    onDeleteFile: (File, () -> Unit) -> Unit,
    onUploadFile: (File) -> Unit,
    onUploadFileWithContext: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    var recordings by remember { mutableStateOf(listOf<File>()) }
    var fileToDelete by remember { mutableStateOf<File?>(null) }
    var convertingFile by remember { mutableStateOf<File?>(null) }
    var convertProgress by remember { mutableStateOf<Float?>(null) }

    // Multimodal action dialog state
    var multiModalTarget by remember { mutableStateOf<File?>(null) }
    var multiModalAction by remember { mutableStateOf(MultiModalAction.SHARE) }
    var includeContextData by remember { mutableStateOf(true) }

    fun refreshList() {
        val downloadDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        recordings = downloadDir
            .listFiles { file ->
                file.isFile && file.name.endsWith(".opus", ignoreCase = true)
            }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    LaunchedEffect(Unit) { refreshList() }

    // ── Delete confirmation dialog ─────────────────────────────────────────
    fileToDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            shape = RoundedCornerShape(16.dp),
            title = {
                Text("删除录音", fontWeight = FontWeight.SemiBold)
            },
            text = {
                Text(
                    "将删除「${file.nameWithoutExtension}」及其所有关联文件（含 GPS/IMU 数据），无法恢复。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteFile(file) {
                        refreshList()
                        fileToDelete = null
                    }
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) { Text("取消") }
            }
        )
    }

    // ── Multimodal share/upload dialog ────────────────────────────────────
    multiModalTarget?.let { file ->
        val hasCtx = File(
            file.parentFile,
            "${file.nameWithoutExtension}$CONTEXT_DATA_SUFFIX"
        ).exists()
        val actionLabel = if (multiModalAction == MultiModalAction.SHARE) "分享" else "上传"

        AlertDialog(
            onDismissRequest = { multiModalTarget = null },
            shape = RoundedCornerShape(16.dp),
            title = {
                Text("选择${actionLabel}内容", fontWeight = FontWeight.SemiBold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "音频文件 (.opus)  ✓",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (hasCtx) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = includeContextData,
                                onCheckedChange = { includeContextData = it }
                            )
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "上下文数据 (.bin)  GPS + IMU",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        Text(
                            "（未发现 GPS/IMU 上下文数据文件）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val withCtx = hasCtx && includeContextData
                    if (multiModalAction == MultiModalAction.SHARE) {
                        if (withCtx) onShareFileWithContext(file) else onShareFile(file)
                    } else {
                        if (withCtx) onUploadFileWithContext(file) else onUploadFile(file)
                    }
                    multiModalTarget = null
                }) {
                    Text(actionLabel, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { multiModalTarget = null }) { Text("取消") }
            }
        )
    }

    // ── Main content ───────────────────────────────────────────────────────
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = "录音文件",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "${recordings.size} 个文件",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
        )
        Spacer(Modifier.height(12.dp))

        if (recordings.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "暂无录音文件",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(recordings, key = { it.absolutePath }) { file ->
                    val hasContextData = File(
                        file.parentFile,
                        "${file.nameWithoutExtension}$CONTEXT_DATA_SUFFIX"
                    ).exists()
                    RecordingItem(
                        file = file,
                        hasContextData = hasContextData,
                        onPlay = {
                            convertingFile = file
                            convertProgress = 0f
                            onPlayFile(file) { current, total ->
                                if (total > 0) convertProgress =
                                    ((current + 1f) / total).coerceIn(0f, 1f)
                            }
                        },
                        onShare = {
                            includeContextData = hasContextData
                            multiModalAction = MultiModalAction.SHARE
                            multiModalTarget = file
                        },
                        onDelete = { fileToDelete = file },
                        onUpload = {
                            includeContextData = hasContextData
                            multiModalAction = MultiModalAction.UPLOAD
                            multiModalTarget = file
                        }
                    )
                    if (convertingFile == file && convertProgress != null) {
                        LinearProgressIndicator(
                            progress = { convertProgress!! },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}
