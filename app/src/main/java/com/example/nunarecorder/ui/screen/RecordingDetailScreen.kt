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
import com.example.nunarecorder.session.SessionPaths
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
    onBack: () -> Unit,
    playback: SegmentPlaybackState?,
    onPlaySegment: (segmentIndex: Int, audioRelPath: String) -> Unit,
    onStopPlayback: () -> Unit,
    modifier: Modifier = Modifier
) {
    var manifest by remember { mutableStateOf(session.manifest) }
    var vadData by remember { mutableStateOf<VadPrelabelData?>(null) }
    var rows by remember { mutableStateOf(listOf<SegmentDetailRow>()) }
    var resumeMessage by remember { mutableStateOf<String?>(null) }

    fun reload() {
        manifest = SessionManifest.load(SessionPaths.manifestFile(session.dir)) ?: session.manifest
        vadData = VadPrelabelReader.load(session.dir)
        val vadByIndex = vadData?.segments?.associateBy { it.index } ?: emptyMap()
        rows = manifest.segments.map { seg ->
            SegmentDetailRow(
                index = seg.index,
                audioFile = seg.file,
                startMs = seg.startMs,
                endMs = seg.endMs,
                durationMs = seg.durationMs,
                bytes = seg.bytes,
                vad = vadByIndex[seg.index]
            )
        }
    }

    LaunchedEffect(session.dir.absolutePath) { reload() }

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
                "预标注详情",
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
            vadComplete = VadResumeHelper.isVadComplete(session.dir),
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
            "分段 VAD 结果",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(6.dp))

        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无音频分段", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(rows, key = { it.index }) { row ->
                    val pb = playback
                    val isPlaying = pb?.segmentIndex == row.index
                    val isConverting = isPlaying && pb?.converting == true
                    SegmentVadCard(
                        row = row,
                        isPlaying = isPlaying,
                        isConverting = isConverting,
                        onClick = { onPlaySegment(row.index, row.audioFile) }
                    )
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
    vadComplete: Boolean,
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
            InfoLine("设备", manifest.deviceName)
            InfoLine("开始时间", dateStr)
            InfoLine("音频段数", "$total 段（每段约 1 分钟）")
            InfoLine("上下文", if (hasContext) "GPS + IMU + 活动 (${SessionPaths.CONTEXT_FILE})" else "无")
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
    onClick: () -> Unit
) {
    val vad = row.vad
    val hasSpeech = vad?.hasSpeech == true
    val pending = vad == null || vad.status != "ok"
    val failed = vad?.status == "failed"

    val accent = when {
        failed -> MaterialTheme.colorScheme.error
        pending -> MaterialTheme.colorScheme.outline
        hasSpeech -> NunaSuccess
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    }
    val statusLabel = when {
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
                        "语音占比 ${(vad.speechRatio * 100).toInt()}% · 约 ${vad.speechMs / 1000}s 有声",
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
