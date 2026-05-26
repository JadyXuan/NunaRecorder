package com.example.nunarecorder.ui.screen

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nunarecorder.audio.SegmentPlaybackState
import com.example.nunarecorder.data.RecordingEntry
import java.io.File
import com.example.nunarecorder.migration.MigrationCoordinator
import com.example.nunarecorder.sync.SessionSyncCoordinator
import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionPaths
import com.example.nunarecorder.ui.components.RecordingItem

private enum class MultiModalAction { SHARE, UPLOAD }

@Composable
fun RecordingsScreen(
    segmentPlayback: SegmentPlaybackState?,
    onPlaySegment: (RecordingEntry.Session, Int, String) -> Unit,
    onStopPlayback: () -> Unit,
    onShareEntry: (RecordingEntry, withContext: Boolean, withVad: Boolean) -> Unit,
    onDeleteEntry: (RecordingEntry, () -> Unit) -> Unit,
    onUploadEntry: (RecordingEntry, withContext: Boolean, withVad: Boolean) -> Unit,
    onMigrateLegacy: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    var entries by remember { mutableStateOf(listOf<RecordingEntry>()) }
    var entryToDelete by remember { mutableStateOf<RecordingEntry?>(null) }
    var multiModalTarget by remember { mutableStateOf<RecordingEntry?>(null) }
    var multiModalAction by remember { mutableStateOf(MultiModalAction.SHARE) }
    var includeContextData by remember { mutableStateOf(true) }
    var includeVadPrelabel by remember { mutableStateOf(true) }
    var detailSession by remember { mutableStateOf<RecordingEntry.Session?>(null) }

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
            SessionSyncCoordinator.Phase.DONE, SessionSyncCoordinator.Phase.ERROR -> {
                refreshList()
                SessionSyncCoordinator.clearDoneState()
            }
            else -> Unit
        }
    }

    detailSession?.let { session ->
        RecordingDetailScreen(
            session = session,
            onBack = {
                onStopPlayback()
                detailSession = null
                refreshList()
            },
            playback = segmentPlayback?.takeIf { it.sessionDirPath == session.dir.absolutePath },
            onPlaySegment = { index, rel -> onPlaySegment(session, index, rel) },
            onStopPlayback = onStopPlayback,
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    entryToDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { entryToDelete = null },
            shape = RoundedCornerShape(16.dp),
            title = { Text("删除录音", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    when (entry) {
                        is RecordingEntry.Session ->
                            "将删除整个会话文件夹「${entry.displayName}」及其中所有分段、上下文与 VAD 预标注。"
                        is RecordingEntry.LegacyOpus ->
                            "将删除「${entry.displayName}」及同名关联文件。"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteEntry(entry) {
                        refreshList()
                        entryToDelete = null
                    }
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { entryToDelete = null }) { Text("取消") }
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

    Column(
        modifier = modifier
            .fillMaxSize()
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
                        syncStatus = persistedSync,
                        isSyncing = syncing,
                        syncProgress = syncProgress,
                        syncMessage = syncMessage,
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
                            { onMigrateLegacy(entry.opusFile) }
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
