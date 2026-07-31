package com.example.nunarecorder.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nunarecorder.audio.SegmentPlaybackState
import com.example.nunarecorder.data.RecordingEntry
import com.example.nunarecorder.migration.MigrateOptions
import com.example.nunarecorder.migration.MigrationCoordinator
import com.example.nunarecorder.recording.SegmentDeleter
import com.example.nunarecorder.sync.SessionSyncCoordinator
import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionPaths
import com.example.nunarecorder.ui.components.RecordingItem
import com.example.nunarecorder.ui.LiveRecordingUiStats
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class MultiModalAction { SHARE, UPLOAD }

@Composable
fun RecordingsScreen(
    segmentPlayback: SegmentPlaybackState?,
    onPlaySegment: (RecordingEntry.Session, Int, String) -> Unit,
    onStopPlayback: () -> Unit,
    onShareEntry: (RecordingEntry, withContext: Boolean, withVad: Boolean) -> Unit,
    onDeleteEntry: (RecordingEntry, () -> Unit) -> Unit,
    onUploadEntry: (RecordingEntry, withContext: Boolean, withVad: Boolean) -> Unit,
    onMigrateLegacy: (File, MigrateOptions) -> Unit,
    activeRecordingPath: String?,
    liveRecordingStats: LiveRecordingUiStats? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf(listOf<RecordingEntry>()) }
    var entryToDelete by remember { mutableStateOf<RecordingEntry?>(null) }
    var multiModalTarget by remember { mutableStateOf<RecordingEntry?>(null) }
    var multiModalAction by remember { mutableStateOf(MultiModalAction.SHARE) }
    var includeContextData by remember { mutableStateOf(true) }
    var includeVadPrelabel by remember { mutableStateOf(true) }
    var detailSession by remember { mutableStateOf<RecordingEntry.Session?>(null) }

    var migrateTarget by remember { mutableStateOf<RecordingEntry.LegacyOpus?>(null) }
    var migrateDoSplit by remember { mutableStateOf(true) }
    var migrateDoVad by remember { mutableStateOf(true) }
    var migrateSegmentSec by remember { mutableStateOf("60") }

    fun refreshList() {
        val sessions = SessionPaths.listSessionDirs().mapNotNull { dir ->
            SessionManifest.load(SessionPaths.manifestFile(dir))?.let {
                RecordingEntry.Session(dir, it)
            }
        }
        val legacy = SessionPaths.listLegacyOpusFiles().map { RecordingEntry.LegacyOpus(it) }
        entries = (sessions + legacy).sortedByDescending { it.sortKey }
    }

    LaunchedEffect(Unit) { refreshList() }

    LaunchedEffect(activeRecordingPath) {
        while (activeRecordingPath != null) {
            refreshList()
            delay(1000)
        }
    }

    LaunchedEffect(activeRecordingPath, entries) {
        detailSession?.let { current ->
            if (activeRecordingPath == current.dir.absolutePath) {
                entries.filterIsInstance<RecordingEntry.Session>()
                    .find { it.dir.absolutePath == current.dir.absolutePath }
                    ?.let { detailSession = it }
            }
        }
    }

    val migrationState by MigrationCoordinator.state.collectAsState()
    val syncState by SessionSyncCoordinator.state.collectAsState()
    LaunchedEffect(migrationState) {
        when (migrationState?.phase) {
            MigrationCoordinator.Phase.DONE, MigrationCoordinator.Phase.ERROR -> {
                refreshList()
                MigrationCoordinator.clearDoneState()
            }
            else -> Unit
        }
    }
    LaunchedEffect(syncState) {
        when (syncState?.phase) {
            SessionSyncCoordinator.Phase.DONE,
            SessionSyncCoordinator.Phase.ERROR,
            SessionSyncCoordinator.Phase.CANCELLED -> {
                refreshList()
                SessionSyncCoordinator.clearDoneState()
            }
            else -> Unit
        }
    }

    fun copyPath(entry: RecordingEntry) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("recording_path", entry.localPath))
        scope.launch {
            snackbarHostState.showSnackbar("已复制路径:\n${entry.localPath}")
        }
    }

    detailSession?.let { session ->
        RecordingDetailScreen(
            session = session,
            isLiveRecording = session.dir.absolutePath == activeRecordingPath,
            liveStats = liveRecordingStats?.takeIf { it.sessionPath == session.dir.absolutePath },
            onBack = {
                onStopPlayback()
                detailSession = null
                refreshList()
            },
            playback = segmentPlayback?.takeIf { it.sessionDirPath == session.dir.absolutePath },
            onPlaySegment = { index, rel -> onPlaySegment(session, index, rel) },
            onStopPlayback = onStopPlayback,
            onDeleteSegment = { index ->
                val result = SegmentDeleter.deleteSegment(session.dir, index)
                scope.launch { snackbarHostState.showSnackbar(result.message) }
                refreshList()
                result.ok
            },
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    entryToDelete?.let { entry ->
        // 本地删除以 1 分钟片段为粒度（P1-12）。整会话删除只在片段清空后才允许，
        // 避免一次误触丢掉一整天。
        val remainingSegments = when (entry) {
            is RecordingEntry.Session -> SegmentDeleter.remainingSegmentCount(entry.dir)
            is RecordingEntry.LegacyOpus -> 0
        }
        AlertDialog(
            onDismissRequest = { entryToDelete = null },
            shape = RoundedCornerShape(16.dp),
            title = { Text("删除录音", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    when (entry) {
                        is RecordingEntry.Session ->
                            if (remainingSegments > 0) {
                                "这个会话里还有 $remainingSegments 个 1 分钟片段。" +
                                    "整会话删除会一次丢掉一整天的采集，所以请先进入会话按片段删除，" +
                                    "清空后再删除会话本身。"
                            } else {
                                "将删除空会话文件夹「${entry.displayName}」及其中的上下文与 VAD 预标注。"
                            }
                        is RecordingEntry.LegacyOpus ->
                            "将删除「${entry.displayName}」及同名关联文件。"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    enabled = remainingSegments == 0,
                    onClick = {
                        onDeleteEntry(entry) {
                            refreshList()
                            entryToDelete = null
                        }
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { entryToDelete = null }) { Text("取消") }
            }
        )
    }

    migrateTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { migrateTarget = null },
            shape = RoundedCornerShape(16.dp),
            title = { Text("后处理选项", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ModalOptionRow(
                        checked = migrateDoSplit,
                        enabled = true,
                        onChecked = { migrateDoSplit = it },
                        icon = { Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp)) },
                        label = "切片（转为会话目录 + 分段）"
                    )
                    if (migrateDoSplit) {
                        OutlinedTextField(
                            value = migrateSegmentSec,
                            onValueChange = { migrateSegmentSec = it },
                            label = { Text("切片时长（秒）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    ModalOptionRow(
                        checked = migrateDoVad,
                        enabled = true,
                        onChecked = { migrateDoVad = it },
                        icon = { Icon(Icons.Outlined.Info, null, Modifier.size(18.dp)) },
                        label = "VAD 预标注"
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val sec = migrateSegmentSec.toIntOrNull()?.coerceIn(10, 600) ?: 60
                    onMigrateLegacy(
                        entry.opusFile,
                        MigrateOptions(migrateDoSplit, migrateDoVad, sec)
                    )
                    migrateTarget = null
                }) {
                    Text("开始", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { migrateTarget = null }) { Text("取消") }
            }
        )
    }

    multiModalTarget?.let { entry ->
        val hasCtx = when (entry) {
            is RecordingEntry.Session -> entry.hasContext
            is RecordingEntry.LegacyOpus -> entry.hasContext
        }
        val hasVad = when (entry) {
            is RecordingEntry.Session -> SessionPaths.vadPrelabelFile(entry.dir).exists()
            is RecordingEntry.LegacyOpus -> false
        }
        val isSession = entry is RecordingEntry.Session
        val actionLabel = if (multiModalAction == MultiModalAction.SHARE) "分享" else "上传"

        AlertDialog(
            onDismissRequest = { multiModalTarget = null },
            shape = RoundedCornerShape(16.dp),
            title = { Text("选择${actionLabel}内容", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ModalOptionRow(
                        checked = true,
                        enabled = false,
                        onChecked = {},
                        icon = { Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp)) },
                        label = if (isSession) "音频分段 + manifest" else "音频 (.opus)"
                    )
                    if (hasCtx) {
                        ModalOptionRow(
                            checked = includeContextData,
                            enabled = true,
                            onChecked = { includeContextData = it },
                            icon = { Icon(Icons.Outlined.Info, null, Modifier.size(18.dp)) },
                            label = "上下文 (GPS + IMU + 活动)"
                        )
                    }
                    if (hasVad) {
                        ModalOptionRow(
                            checked = includeVadPrelabel,
                            enabled = true,
                            onChecked = { includeVadPrelabel = it },
                            icon = { Icon(Icons.Outlined.Info, null, Modifier.size(18.dp)) },
                            label = "VAD 预标注"
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ctx = hasCtx && includeContextData
                    val vad = hasVad && includeVadPrelabel
                    if (multiModalAction == MultiModalAction.SHARE) {
                        onShareEntry(entry, ctx, vad)
                    } else {
                        onUploadEntry(entry, ctx, vad)
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text("录音文件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "${entries.size} 项",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
            )
            Spacer(Modifier.height(12.dp))

            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无录音", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(entries, key = {
                        when (it) {
                            is RecordingEntry.Session -> it.dir.absolutePath
                            is RecordingEntry.LegacyOpus -> it.opusFile.absolutePath
                        }
                    }) { entry ->
                        val entryKey = when (entry) {
                            is RecordingEntry.Session -> entry.dir.absolutePath
                            is RecordingEntry.LegacyOpus -> entry.opusFile.absolutePath
                        }
                        val isLive = entry is RecordingEntry.Session &&
                            entry.dir.absolutePath == activeRecordingPath
                        val liveBytes = if (isLive && entry is RecordingEntry.Session) {
                            liveRecordingStats
                                ?.takeIf { it.sessionPath == entry.dir.absolutePath }
                                ?.totalBytes
                                ?: (entry.manifest.segments.sumOf { it.bytes } + entry.manifest.openSegmentBytes)
                        } else null
                        val liveSegs = if (isLive && entry is RecordingEntry.Session) {
                            val stats = liveRecordingStats?.takeIf { it.sessionPath == entry.dir.absolutePath }
                            if (stats != null) {
                                stats.closedSegmentCount + if (stats.openSegmentBytes > 0 || isLive) 1 else 0
                            } else {
                                entry.manifest.segments.size +
                                    if (entry.manifest.recordingActive || entry.manifest.openSegmentBytes > 0) 1 else 0
                            }
                        } else null

                        val syncing = syncState?.targetKey == entryKey &&
                            syncState?.phase == SessionSyncCoordinator.Phase.SYNCING
                        val syncProgress = if (syncing) syncState?.progress else null
                        val syncMessage = if (syncing) syncState?.message else null
                        val persistedSync = when (entry) {
                            is RecordingEntry.Session -> entry.syncStatus
                            is RecordingEntry.LegacyOpus -> null
                        }

                        RecordingItem(
                            entry = entry,
                            isLiveRecording = isLive,
                            liveTotalBytes = liveBytes,
                            liveSegmentCount = liveSegs,
                            onCopyPath = { copyPath(entry) },
                            syncStatus = persistedSync,
                            isSyncing = syncing,
                            syncProgress = syncProgress,
                            syncMessage = syncMessage,
                            onCancelSync = { SessionSyncCoordinator.cancel() },
                            onShare = {
                                includeContextData = when (entry) {
                                    is RecordingEntry.Session -> entry.hasContext
                                    is RecordingEntry.LegacyOpus -> entry.hasContext
                                }
                                includeVadPrelabel = entry is RecordingEntry.Session &&
                                    SessionPaths.vadPrelabelFile(entry.dir).exists()
                                multiModalAction = MultiModalAction.SHARE
                                multiModalTarget = entry
                            },
                            onDelete = { entryToDelete = entry },
                            onUpload = {
                                includeContextData = when (entry) {
                                    is RecordingEntry.Session -> entry.hasContext
                                    is RecordingEntry.LegacyOpus -> entry.hasContext
                                }
                                includeVadPrelabel = entry is RecordingEntry.Session &&
                                    SessionPaths.vadPrelabelFile(entry.dir).exists()
                                multiModalAction = MultiModalAction.UPLOAD
                                multiModalTarget = entry
                            },
                            onMigrate = if (entry is RecordingEntry.LegacyOpus) {
                                {
                                    migrateDoSplit = true
                                    migrateDoVad = true
                                    migrateSegmentSec = "60"
                                    migrateTarget = entry
                                }
                            } else null,
                            migrateEnabled = !(entry is RecordingEntry.LegacyOpus &&
                                MigrationCoordinator.isActiveFor(entry.opusFile.absolutePath)),
                            onOpenDetail = if (entry is RecordingEntry.Session) {
                                { detailSession = entry }
                            } else null
                        )
                        val legacyMigrating = entry is RecordingEntry.LegacyOpus &&
                            migrationState?.opusPath == entry.opusFile.absolutePath &&
                            migrationState?.phase !in setOf(
                                MigrationCoordinator.Phase.DONE,
                                MigrationCoordinator.Phase.ERROR
                            )
                        val legacyMigrateProgress = if (legacyMigrating) migrationState?.progress else null
                        val legacyMigrateMessage = if (legacyMigrating) migrationState?.message else null
                        if (legacyMigrateProgress != null) {
                            LinearProgressIndicator(
                                progress = { legacyMigrateProgress },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            )
                            legacyMigrateMessage?.let { msg ->
                                Text(
                                    msg,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ModalOptionRow(
    checked: Boolean,
    enabled: Boolean,
    onChecked: (Boolean) -> Unit,
    icon: @Composable () -> Unit,
    label: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChecked, enabled = enabled)
        Spacer(Modifier.width(4.dp))
        icon()
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
