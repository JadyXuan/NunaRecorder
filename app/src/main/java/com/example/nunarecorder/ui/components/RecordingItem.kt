package com.example.nunarecorder.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nunarecorder.data.RecordingEntry
import com.example.nunarecorder.sync.SessionSyncStatus
import com.example.nunarecorder.ui.theme.NunaSuccess
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordingItem(
    entry: RecordingEntry,
    onCopyPath: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onUpload: () -> Unit,
    onMigrate: (() -> Unit)? = null,
    migrateEnabled: Boolean = true,
    onOpenDetail: (() -> Unit)? = null,
    syncStatus: SessionSyncStatus? = null,
    isSyncing: Boolean = false,
    syncProgress: Float? = null,
    syncMessage: String? = null,
    /** 取消正在进行的上传；已传完的文件留在服务端，下次续传 */
    onCancelSync: (() -> Unit)? = null,
    isLiveRecording: Boolean = false,
    liveTotalBytes: Long? = null,
    liveSegmentCount: Int? = null,
    modifier: Modifier = Modifier
) {
    val subtitle = when (entry) {
        is RecordingEntry.Session -> {
            val m = entry.manifest
            val date = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(Date(m.startedAtMs))
            if (isLiveRecording) {
                val bytes = liveTotalBytes ?: m.openSegmentBytes
                val segs = liveSegmentCount ?: m.segments.size
                "$date · 录制中 · $segs 段 · ${formatSize(bytes)}"
            } else {
                val vadLabel = when (m.vad.status) {
                    "disabled" -> "未做 VAD"
                    "complete" -> "VAD ${m.vad.speechSegments}/${m.segments.size} 有人声"
                    else -> "VAD ${m.vad.status}"
                }
                "$date · ${m.segments.size} 段 · $vadLabel"
            }
        }
        is RecordingEntry.LegacyOpus -> {
            val date = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(Date(entry.opusFile.lastModified()))
            val kb = entry.opusFile.length() / 1024
            "$date · ${kb} KB · 旧格式"
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isLiveRecording) {
                NunaSuccess.copy(alpha = 0.06f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCopyPath() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            if (isLiveRecording) NunaSuccess.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.List,
                        contentDescription = null,
                        tint = if (isLiveRecording) NunaSuccess else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        entry.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isLiveRecording) NunaSuccess
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Text(
                        "点击复制路径",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                }
                Spacer(Modifier.width(6.dp))
                if (isLiveRecording) {
                    ModalBadge("录制中", NunaSuccess)
                    Spacer(Modifier.width(4.dp))
                }
                if (entry.let {
                        it is RecordingEntry.Session && it.hasContext ||
                            it is RecordingEntry.LegacyOpus && it.hasContext
                    }
                ) {
                    ModalBadge("上下文", NunaSuccess)
                    Spacer(Modifier.width(4.dp))
                }
                when {
                    isSyncing -> {
                        ModalBadge("同步中", MaterialTheme.colorScheme.primary)
                    }
                    syncStatus != null -> {
                        SyncBadge(syncStatus.status, syncStatus.summary)
                    }
                }
                when (entry) {
                    is RecordingEntry.Session -> {
                        if (!isLiveRecording && entry.manifest.segments.isNotEmpty()) {
                            Spacer(Modifier.width(4.dp))
                            ModalBadge("分段", MaterialTheme.colorScheme.primary)
                        }
                        if (entry.manifest.vad.status == "complete") {
                            Spacer(Modifier.width(4.dp))
                            ModalBadge("VAD", MaterialTheme.colorScheme.secondary)
                        }
                    }
                    is RecordingEntry.LegacyOpus -> {
                        ModalBadge("Opus", MaterialTheme.colorScheme.tertiary)
                    }
                }
            }

            if (isLiveRecording) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = NunaSuccess
                )
            }

            if (onMigrate != null) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onMigrate,
                    enabled = migrateEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        if (migrateEnabled) "后处理（切片 / VAD）" else "处理进行中…",
                        fontSize = 12.sp
                    )
                }
            }

            if (onOpenDetail != null) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onOpenDetail,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        if (isLiveRecording) "查看实时进度" else "查看详情 / VAD",
                        fontSize = 12.sp
                    )
                }
            }

            // 判据不能是 summary.failed > 0：**上传在 init 阶段失败时一个文件都不会被标成
            // failed**（它们停在 pending 或上一次的 synced），于是界面只显示
            // "部分同步 (7/7)"、"部分同步 (0/11)"，**一个字的原因都没有**——
            // 而 sync_status.json 里明明写着"令牌与参与者编号不匹配（403）"。
            // 用户 2026-08-15 实测就卡在这里：以为是新旧数据不兼容，实际是入组卡发错了。
            syncStatus?.takeIf { it.status in setOf("partial", "failed") }?.let { sync ->
                Spacer(Modifier.height(6.dp))
                Text(
                    syncHint(sync),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (syncProgress != null) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { syncProgress },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        syncMessage.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 2.dp)
                    )
                    // 上传卡住时得能停下来，而不是只能等（P1-13）
                    if (isSyncing && onCancelSync != null) {
                        TextButton(onClick = onCancelSync) {
                            Text(
                                "取消",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RecordingActionButton(Icons.Outlined.Share, "分享", onShare,
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f),
                    MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(6.dp))
                RecordingActionButton(Icons.Outlined.Send, "上传", onUpload,
                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f),
                    MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(6.dp))
                RecordingActionButton(Icons.Outlined.Delete, "删除", onDelete,
                    MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                    MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun SyncBadge(status: String, summary: SessionSyncStatus.Summary) {
    val (label, color) = when (status) {
        "synced" -> "已同步" to NunaSuccess
        "syncing" -> "同步中" to MaterialTheme.colorScheme.primary
        "partial" -> "部分同步" to MaterialTheme.colorScheme.tertiary
        "failed" -> "同步失败" to MaterialTheme.colorScheme.error
        else -> "未同步" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    }
    val detail = if (status == "partial" || status == "failed") {
        " (${summary.synced}/${summary.total})"
    } else ""
    ModalBadge(label + detail, color)
}

private fun syncHint(sync: SessionSyncStatus): String {
    val failed = sync.files.filter { it.status == "failed" }
    // 整次上传失败（init / commit 没过）时没有任何单个文件是 failed，
    // 此时唯一的信息在 last_error 里。它必须显示出来，否则参与者只看到
    // 一个不带原因的"部分同步"，而那条信息恰恰是他能拿去找研究员的唯一线索。
    if (failed.isEmpty()) {
        return sync.lastError?.takeIf { it.isNotBlank() } ?: "上传未完成，原因未记录"
    }
    val names = failed.take(3).joinToString { it.path.substringAfterLast('/') }
    val more = if (failed.size > 3) " 等" else ""
    return "未同步成功: $names$more (${failed.size} 个文件)"
}

@Composable
private fun ModalBadge(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RecordingActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(36.dp),
            colors = IconButtonDefaults.iconButtonColors(containerColor = containerColor, contentColor = contentColor)
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = contentColor.copy(alpha = 0.8f))
    }
}
