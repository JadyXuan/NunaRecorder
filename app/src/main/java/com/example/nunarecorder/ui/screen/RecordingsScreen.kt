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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.nunarecorder.data.RecordingEntryLoader
import com.example.nunarecorder.migration.MigrateOptions
import com.example.nunarecorder.migration.MigrationCoordinator
import com.example.nunarecorder.recording.SegmentDeleter
import com.example.nunarecorder.recording.SyncedSessionCleaner
import com.example.nunarecorder.sync.SessionSyncCoordinator
import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionPaths
import com.example.nunarecorder.ui.components.RecordingItem
import com.example.nunarecorder.ui.LiveRecordingUiStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class MultiModalAction { SHARE, UPLOAD }

@Composable
fun RecordingsScreen(
    segmentPlayback: SegmentPlaybackState?,
    onPlaySegment: (RecordingEntry.Session, Int, String) -> Unit,
    onStopPlayback: () -> Unit,
    onShareEntry: (
        RecordingEntry, withContext: Boolean, withVad: Boolean, withMmWave: Boolean
    ) -> Unit,
    onDeleteEntry: (RecordingEntry, () -> Unit) -> Unit,
    onUploadEntry: (
        RecordingEntry, withContext: Boolean, withVad: Boolean, withMmWave: Boolean
    ) -> Unit,
    /** 一键上传所有未同步会话 */
    onUploadAllPending: () -> Unit = {},
    /** 一键清理已确认同步的会话，返回清理结果描述 */
    onDeleteSynced: (onDone: () -> Unit) -> Unit = { it() },
    onMigrateLegacy: (File, MigrateOptions) -> Unit,
    activeRecordingPath: String?,
    liveRecordingStats: LiveRecordingUiStats? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // neverEqualPolicy 是必须的，不是保险起见。
    // RecordingEntry.Session 是 data class(dir, manifest)，**syncStatus 不参与 equals**
    // （它是构造时读的 body val）。所以上传完成后重新加载出来的列表与旧列表判定相等，
    // 赋值成了空操作，界面根本不重组——用户 2026-08-09 两次报"全部上传完还是显示 10，
    // 切换界面刷新后才正常"，切标签页会重建组件，所以看起来像"刷新一下就好了"。
    var entries by remember {
        mutableStateOf(listOf<RecordingEntry>(), policy = androidx.compose.runtime.neverEqualPolicy())
    }
    var entryToDelete by remember { mutableStateOf<RecordingEntry?>(null) }
    var multiModalTarget by remember { mutableStateOf<RecordingEntry?>(null) }
    var multiModalAction by remember { mutableStateOf(MultiModalAction.SHARE) }
    var includeContextData by remember { mutableStateOf(true) }
    var includeVadPrelabel by remember { mutableStateOf(true) }
    var includeMmWaveData by remember { mutableStateOf(true) }
    var detailSession by remember { mutableStateOf<RecordingEntry.Session?>(null) }

    var migrateTarget by remember { mutableStateOf<RecordingEntry.LegacyOpus?>(null) }
    var migrateDoSplit by remember { mutableStateOf(true) }
    var migrateDoVad by remember { mutableStateOf(true) }
    var migrateSegmentSec by remember { mutableStateOf("60") }

    // 列表加载全部走 IO 线程 + 缓存。构造 RecordingEntry.Session 本身要读盘，
    // 解析 manifest 更贵，而录制期间这个刷新每秒跑一次。
    val loader = remember { RecordingEntryLoader() }
    // 每完成一次刷新就 +1，用来触发那些"列表变了才该重算"的后台计算，
    // 而不是拿 entries 当 key —— entries 每秒都是新对象，会把重算也变成每秒一次。
    var listRevision by remember { mutableIntStateOf(0) }

    suspend fun reload() {
        entries = withContext(Dispatchers.IO) { loader.load() }
        listRevision++
    }

    fun refreshList() {
        scope.launch { reload() }
    }

    LaunchedEffect(Unit) { reload() }

    LaunchedEffect(activeRecordingPath) {
        while (activeRecordingPath != null) {
            reload()
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
    // 上传状态一变就重载。上传只改 sync_status.json，既不动 manifest 也不动 entries，
    // 没有这一条的话"待上传 / 可清理"要等用户切标签页才更新
    // （用户 2026-08-09 实测："全部上传完，全部上传还是显示为 10"）。
    LaunchedEffect(syncState?.phase, syncState?.targetKey) { reload() }
    var confirmCleanup by remember { mutableStateOf(false) }
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
            SessionSyncCoordinator.Phase.ERROR,
            SessionSyncCoordinator.Phase.TOKEN_REJECTED -> {
                // 先把原因弹出来再清状态。原来失败和成功走同一条清理分支，
                // 于是 Phase.ERROR 刚置上就被 clearDoneState() 抹掉，
                // **失败原因在界面上一闪都没有**——"一键上传没反应"就是这么来的。
                val why = syncState?.message
                refreshList()
                SessionSyncCoordinator.clearDoneState()
                if (!why.isNullOrBlank()) {
                    snackbarHostState.showSnackbar(why, duration = SnackbarDuration.Long)
                }
            }
            SessionSyncCoordinator.Phase.DONE,
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

    if (confirmCleanup) {
        // 对话框里现算一次：数量是刚刚后台算的，体积要遍历文件，放在这里而不是
        // 每次重组都算
        var candidates by remember { mutableStateOf<List<SyncedSessionCleaner.Candidate>>(emptyList()) }
        LaunchedEffect(Unit) {
            candidates = withContext(Dispatchers.IO) { SyncedSessionCleaner.listDeletable() }
        }
        val freed = candidates.sumOf { it.bytes }
        AlertDialog(
            onDismissRequest = { confirmCleanup = false },
            shape = RoundedCornerShape(16.dp),
            title = { Text("清理已同步的录音", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "将删除 ${candidates.size} 个**服务器已确认收到**的会话，" +
                        "释放约 ${LiveRecordingUiStats.formatBytes(freed)}。\n\n" +
                        "只有 commit 返回 synced、且每个文件都核对过的会话才会被删。" +
                        "上传失败或只传了一半的一律保留——手机上那份可能是唯一的一份。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSynced { refreshList() }
                    confirmCleanup = false
                }) {
                    Text("清理", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmCleanup = false }) { Text("取消") } }
        )
    }

    entryToDelete?.let { entry ->
        // 本地删除以 1 分钟片段为粒度（P1-12）。整会话删除只在片段清空后才允许，
        // 避免一次误触丢掉一整天。
        // 组合期不读盘：这里要解析整份 manifest，一天 900 段时对话框会卡着才弹出来
        var remainingSegments by remember(entry) { mutableIntStateOf(-1) }
        LaunchedEffect(entry) {
            remainingSegments = withContext(Dispatchers.IO) {
                when (entry) {
                    is RecordingEntry.Session -> SegmentDeleter.remainingSegmentCount(entry.dir)
                    is RecordingEntry.LegacyOpus -> 0
                }
            }
        }
        AlertDialog(
            onDismissRequest = { entryToDelete = null },
            shape = RoundedCornerShape(16.dp),
            title = { Text("删除录音", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    when (entry) {
                        is RecordingEntry.Session ->
                            if (remainingSegments < 0) {
                                "正在统计这个会话里还剩多少片段…"
                            } else if (remainingSegments > 0) {
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
        val mmWave = (entry as? RecordingEntry.Session)?.mmWave?.takeIf { it.hasData }
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
                    mmWave?.let {
                        ModalOptionRow(
                            checked = includeMmWaveData,
                            enabled = true,
                            onChecked = { includeMmWaveData = it },
                            icon = { Icon(Icons.Outlined.Info, null, Modifier.size(18.dp)) },
                            label = "毫米波 (${it.describe()})"
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
                    val mm = mmWave != null && includeMmWaveData
                    if (multiModalAction == MultiModalAction.SHARE) {
                        onShareEntry(entry, ctx, vad, mm)
                    } else {
                        onUploadEntry(entry, ctx, vad, mm)
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

            // 未同步 / 已同步各有多少，决定两个批量操作是否可点
            // 同 uploadAllPending：只排除此刻真正在录的那一个，不信 manifest 里的
            // recordingActive——被杀过的会话那个标志永远是 true，会被静默漏掉。
            val pendingCount = entries.count {
                it is RecordingEntry.Session &&
                    it.syncStatus?.status != "synced" &&
                    it.dir.absolutePath != activeRecordingPath
            }
            // 原来是 remember(entries) 里同步算：
            // ① 它遍历每个会话的全部文件求体积，几百个文件时会卡住主线程；
            // ② entries 在批量上传后往往没变（还是同一批目录），于是一直显示旧的 0，
            //    要等下一次列表刷新才变——用户看到的"过一小会才好"就是这个。
            // 改成后台线程算，并且以上传状态为触发条件。
            var deletableCount by remember { mutableStateOf(0) }
            LaunchedEffect(listRevision, syncState?.phase) {
                // countDeletable 不遍历文件求体积——只有对话框需要体积。
                // 原来这里调 listDeletable()，每个已同步会话都要 walk 一遍全部文件；
                // 又以 entries 为 key，于是录制期间每秒把几千次 stat 跑一遍。
                deletableCount = withContext(Dispatchers.IO) { SyncedSessionCleaner.countDeletable() }
            }
            Text(
                "${entries.size} 项 · 待上传 $pendingCount · 可清理 $deletableCount",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
            )
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onUploadAllPending,
                    enabled = pendingCount > 0 && syncState?.phase != SessionSyncCoordinator.Phase.SYNCING,
                    modifier = Modifier.weight(1f)
                ) { Text("全部上传（$pendingCount）", style = MaterialTheme.typography.labelMedium) }

                OutlinedButton(
                    onClick = { confirmCleanup = true },
                    enabled = deletableCount > 0,
                    modifier = Modifier.weight(1f)
                ) { Text("清理已同步（$deletableCount）", style = MaterialTheme.typography.labelMedium) }
            }
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
                                includeMmWaveData = entry is RecordingEntry.Session &&
                                    entry.mmWave.hasData
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
                                includeMmWaveData = entry is RecordingEntry.Session &&
                                    entry.mmWave.hasData
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
