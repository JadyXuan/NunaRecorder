package com.example.nunarecorder.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nunarecorder.audio.SegmentPlaybackState
import com.example.nunarecorder.data.RecordingEntry
import com.example.nunarecorder.session.AudioSegmentEntry
import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionMmWaveStatus
import com.example.nunarecorder.session.SessionPaths
import com.example.nunarecorder.ui.LiveRecordingUiStats
import com.example.nunarecorder.ui.theme.NunaSuccess
import com.example.nunarecorder.vad.VadJobQueue
import com.example.nunarecorder.vad.VadPrelabelData
import com.example.nunarecorder.vad.VadPrelabelReader
import com.example.nunarecorder.vad.VadPrelabelSegment
import com.example.nunarecorder.vad.VadResumeHelper
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 合并 manifest 音频段与 VAD 预标注，用于详情展示 */
data class SegmentDetailRow(
    val index: Int,
    val audioFile: String,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val bytes: Long,
    val vad: VadPrelabelSegment?
)

@Composable
fun RecordingDetailScreen(
    session: RecordingEntry.Session,
    isLiveRecording: Boolean = false,
    liveStats: LiveRecordingUiStats? = null,
    onBack: () -> Unit,
    playback: SegmentPlaybackState?,
    onPlaySegment: (segmentIndex: Int, audioRelPath: String) -> Unit,
    onStopPlayback: () -> Unit,
    /** 删除单个 1 分钟片段，返回是否成功 */
    onDeleteSegment: (segmentIndex: Int) -> Boolean = { false },
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var manifest by remember { mutableStateOf(session.manifest) }
    var vadData by remember { mutableStateOf<VadPrelabelData?>(null) }
    var rows by remember { mutableStateOf(listOf<SegmentDetailRow>()) }
    var resumeMessage by remember { mutableStateOf<String?>(null) }
    var segmentToDelete by remember { mutableStateOf<SegmentDetailRow?>(null) }
    var confirmSpeechPurge by remember { mutableStateOf(false) }

    fun reload() {
        manifest = SessionManifest.load(SessionPaths.manifestFile(session.dir)) ?: session.manifest
        vadData = VadPrelabelReader.load(session.dir)
        val vadByIndex = vadData?.segments?.associateBy { it.index } ?: emptyMap()
        val list = manifest.segments.map { seg ->
            SegmentDetailRow(
                index = seg.index,
                audioFile = seg.file,
                startMs = seg.startMs,
                endMs = seg.endMs,
                durationMs = seg.durationMs,
                bytes = seg.bytes,
                vad = vadByIndex[seg.index]
            )
        }.toMutableList()
        if (manifest.recordingActive || isLiveRecording) {
            val idx = manifest.openSegmentIndex ?: list.size
            val openBytes = liveStats?.openSegmentBytes ?: manifest.openSegmentBytes
            val rel = if (manifest.segmentDurationMs < Long.MAX_VALUE / 2) {
                SessionPaths.segmentRelativePath(idx)
            } else {
                SessionPaths.STREAM_OPUS_FILE
            }
            list.add(
                SegmentDetailRow(
                    index = idx,
                    audioFile = rel,
                    startMs = idx * manifest.segmentDurationMs.coerceAtMost(Long.MAX_VALUE),
                    endMs = 0L,
                    durationMs = if (openBytes > 0) openBytes / 80 * 20L else 0L,
                    bytes = openBytes,
                    vad = null
                )
            )
        }
        rows = list
    }

    LaunchedEffect(session.dir.absolutePath) { reload() }

    LaunchedEffect(session.dir.absolutePath, isLiveRecording, liveStats) {
        while (true) {
            reload()
            val m = SessionManifest.load(SessionPaths.manifestFile(session.dir))
            val stillLive = isLiveRecording || (m?.recordingActive == true)
            if (!stillLive) break
            delay(800)
        }
    }

    // VAD 仍在队列中时定时刷新列表
    LaunchedEffect(manifest.vad.status) {
        if (manifest.vad.status == "running" || manifest.vad.status == "partial") {
            while (true) {
                delay(2500)
                reload()
                val m = SessionManifest.load(SessionPaths.manifestFile(session.dir))
                if (m?.vad?.status == "complete") {
                    manifest = m
                    break
                }
                manifest = m ?: manifest
            }
        }
    }

    val showingLive = isLiveRecording || manifest.recordingActive

    // 隐私兜底：一次删掉所有有语音的段。
    // 用户 2026-08-10 明确要的：「如果隐私优先、那段内容真的很敏感」，
    // 代价是少一点数据和报酬，但隐私完全包住。**代价必须在按下之前说清楚**——
    // 一个删掉大半天数据的按钮不能只写"确定吗"。
    if (confirmSpeechPurge) {
        val (speechCount, total) = remember(rows) {
            com.example.nunarecorder.recording.SegmentDeleter.speechSegmentCount(session.dir)
        }
        val unknown = remember(rows) { rows.count { it.vad == null || it.vad.status != "ok" } }
        AlertDialog(
            onDismissRequest = { confirmSpeechPurge = false },
            title = { Text("删除所有有说话的片段", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "将删除这个会话里 $speechCount 个检测到说话的片段（共 $total 段），" +
                        "约 ${speechCount} 分钟音频。删除会被记入 manifest，**无法撤销**。\n\n" +
                        "保留下来的是没有检测到说话的部分——环境声仍然有研究价值，" +
                        "但语音内容会全部消失，这一段的报酬也会相应减少。\n\n" +
                        (if (unknown > 0)
                            "另有 $unknown 段还没分析完，这次不会动它们：" +
                                "我们并不知道它们有没有语音，按「没有」处理会漏删。等分析完再来一次。"
                        else ""),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSpeechPurge = false
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            com.example.nunarecorder.recording.SegmentDeleter
                                .deleteSpeechSegments(session.dir)
                        }
                        resumeMessage = "已删除 ${r.deleted} 个有说话的片段" +
                            (if (r.failed.isNotEmpty()) "，${r.failed.size} 个失败" else "") +
                            (if (r.unknown > 0) "；${r.unknown} 段还没分析完，未处理" else "")
                        reload()
                    }
                }) {
                    Text("全部删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSpeechPurge = false }) { Text("取消") }
            }
        )
    }

    // 本地删除粒度与数据粒度一致：一次一分钟。整会话删除在列表页被挡住，
    // 必须先在这里把片段删干净。
    segmentToDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { segmentToDelete = null },
            title = { Text("删除这一分钟", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "将删除第 ${row.index} 段（会话开始后第 ${row.index + 1} 分钟）的音频、" +
                        "manifest 条目和 VAD 预标注。删除会被记入 manifest，无法撤销。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (onDeleteSegment(row.index)) reload()
                    segmentToDelete = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { segmentToDelete = null }) { Text("取消") }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("← 返回", fontWeight = FontWeight.Medium)
            }
            Text(
                if (showingLive) "录制进度" else "预标注详情",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            session.displayName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(start = 8.dp)
        )

        Spacer(Modifier.height(8.dp))

        SummaryCard(
            manifest = manifest,
            vadData = vadData,
            hasContext = session.hasContext,
            mmWave = session.mmWave,
            vadComplete = VadResumeHelper.isVadComplete(session.dir),
            isLiveRecording = showingLive,
            liveStats = liveStats,
            onResumeVad = {
                val n = VadJobQueue.enqueuePendingSegments(session.dir)
                resumeMessage = if (n > 0) "已加入队列：$n 段待分析" else "没有待分析的段"
                reload()
            }
        )
        resumeMessage?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        playback?.let { pb ->
            PlaybackBar(
                playback = pb,
                onStop = onStopPlayback
            )
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(4.dp))

        Text(
            if (showingLive) "音频分段（VAD 在段封口后分析）" else "分段 VAD 结果",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(6.dp))

        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (showingLive) "等待音频数据…" else "暂无音频分段",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                // 隐私兜底入口。放在列表最上面：需要用它的时候人是慌的
                // （"刚说完，意识到有话不能说"），不该让他往下翻。
                item {
                    val speechRows = rows.count { it.vad?.hasSpeech == true }
                    if (speechRows > 0 && !showingLive) {
                        OutlinedButton(
                            onClick = { confirmSpeechPurge = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "删除所有有说话的片段（$speechRows 段）",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
                items(rows, key = { it.index }) { row ->
                    val pb = playback
                    val isPlaying = pb?.segmentIndex == row.index
                    val isConverting = isPlaying && pb?.converting == true
                    val isOpen = row.endMs == 0L && showingLive
                    Column {
                        SegmentVadCard(
                            row = row,
                            isPlaying = isPlaying,
                            isConverting = isConverting,
                            isLiveOpen = isOpen,
                            onClick = { onPlaySegment(row.index, row.audioFile) }
                        )
                        if (!isOpen && !showingLive) {
                            TextButton(
                                onClick = { segmentToDelete = row },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text(
                                    "删除这一分钟",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    manifest: SessionManifest,
    vadData: VadPrelabelData?,
    hasContext: Boolean,
    mmWave: SessionMmWaveStatus,
    vadComplete: Boolean,
    isLiveRecording: Boolean = false,
    liveStats: LiveRecordingUiStats? = null,
    onResumeVad: () -> Unit
) {
    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        .format(Date(manifest.startedAtMs))
    val vadStatus = manifest.vad.status
    val speech = vadData?.summary?.speechSegments ?: manifest.vad.speechSegments
    val total = vadData?.summary?.totalSegments ?: manifest.segments.size
    val analyzed = vadData?.summary?.analyzedSegments ?: 0
    val pendingCount = (total - analyzed).coerceAtLeast(0)

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("会话信息", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            if (isLiveRecording) {
                val totalBytes = liveStats?.totalBytes
                    ?: (manifest.segments.sumOf { it.bytes } + manifest.openSegmentBytes)
                val openBytes = liveStats?.openSegmentBytes ?: manifest.openSegmentBytes
                Text(
                    if (openBytes > 0 || totalBytes > 0) {
                        "● 正在录制 · 已收 ${formatBytes(totalBytes)}"
                    } else {
                        "● 正在录制 · 等待数据…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NunaSuccess,
                    fontWeight = FontWeight.SemiBold
                )
            }
            InfoLine("设备", manifest.deviceName)
            manifest.deviceAddress?.let { InfoLine("MAC", it) }
            InfoLine("开始时间", dateStr)
            val segLabel = if (manifest.segmentDurationMs >= Long.MAX_VALUE / 2) {
                "${total} 段（整段录制）"
            } else {
                "$total 段（约 ${manifest.segmentDurationMs / 1000}s/段）"
            }
            InfoLine("音频", segLabel)
            InfoLine("上下文", if (hasContext) "GPS + IMU + 活动 (${SessionPaths.CONTEXT_FILE})" else "无")
            // 毫米波原来在整个界面上完全不可见，参与者无从知道它存不存在、采到没有
            if (mmWave.enabled) InfoLine("毫米波", mmWave.describe())
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("VAD (Silero)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            InfoLine("状态", vadStatusText(vadStatus, vadComplete))
            InfoLine("引擎", "${vadData?.engine ?: "silero"} ${vadData?.engineVersion ?: ""}".trim())
            InfoLine("有人声段", "$speech / $total")
            InfoLine("已分析", "$analyzed / $total")
            if (vadData != null && total > 0) {
                LinearProgressIndicator(
                    progress = { analyzed.toFloat() / total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
            if (!vadComplete && pendingCount > 0) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onResumeVad, modifier = Modifier.fillMaxWidth()) {
                    Text("继续 VAD 分析（剩余 $pendingCount 段）")
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.width(88.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PlaybackBar(
    playback: SegmentPlaybackState,
    onStop: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (playback.converting) "正在转码…" else "正在播放",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "段 ${playback.segmentIndex} · ${playback.segmentLabel.substringAfterLast('/')}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
            }
            FilledTonalButton(onClick = onStop) {
                Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("停止")
            }
        }
    }
}

@Composable
private fun SegmentVadCard(
    row: SegmentDetailRow,
    isPlaying: Boolean,
    isConverting: Boolean,
    isLiveOpen: Boolean = false,
    onClick: () -> Unit
) {
    val vad = row.vad
    val hasSpeech = vad?.hasSpeech == true
    val pending = vad == null || vad.status != "ok"
    val failed = vad?.status == "failed"

    val accent = when {
        isLiveOpen -> NunaSuccess
        failed -> MaterialTheme.colorScheme.error
        pending -> MaterialTheme.colorScheme.outline
        hasSpeech -> NunaSuccess
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    }
    val statusLabel = when {
        isLiveOpen && row.bytes == 0L -> "等待数据"
        isLiveOpen -> "写入中"
        vad == null -> "待分析"
        failed -> "分析失败"
        vad.status != "ok" -> vad.status
        hasSpeech -> "有人声"
        else -> "无人声"
    }

    val containerColor = if (isPlaying) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(accent)
                    .align(Alignment.CenterVertically)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "段 ${row.index} · ${row.audioFile.substringAfterLast('/')}",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isConverting) {
                            Text(
                                "转码中",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        } else if (isPlaying) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "播放中",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "播放中",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        } else {
                            Icon(
                                Icons.Outlined.PlayArrow,
                                contentDescription = "播放",
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                statusLabel,
                                color = accent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                Text(
                    "${formatMs(row.startMs)} – ${formatMs(row.endMs)} · ${row.durationMs / 1000}s · ${row.bytes / 1024} KB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                if (vad != null && vad.status == "ok") {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        // 占比回答不了"我该去听哪里"。用户 2026-08-10 的场景是
                        // "刚说完，意识到有话不能说，立即去找 vad 有说话的部分，还好删"——
                        // 那是个时间窗很短的召回任务，要的是区间不是比例。
                        if (vad.speechIntervals.isNotEmpty()) {
                            "说话在 " + com.example.nunarecorder.vad.SpeechIntervals
                                .describe(vad.speechIntervals) +
                                " · 共 ${vad.speechMs / 1000}s"
                        } else {
                            "语音占比 ${(vad.speechRatio * 100).toInt()}% · 约 ${vad.speechMs / 1000}s 有声"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    LinearProgressIndicator(
                        progress = { vad.speechRatio.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        color = if (hasSpeech) NunaSuccess else MaterialTheme.colorScheme.outline,
                        trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                    )
                    Text(
                        "分析于 ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(vad.analyzedAtMs))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else if (vad?.error != null) {
                    Text(
                        vad.error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    val m = s / 60
    val sec = s % 60
    return "%d:%02d".format(m, sec)
}

private fun vadStatusText(status: String, complete: Boolean): String = when {
    complete -> "已完成"
    status == "running" -> "分析中…"
    status == "partial" -> "部分完成"
    status == "pending" -> "等待分析"
    else -> status
}
