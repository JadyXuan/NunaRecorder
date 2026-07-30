package com.example.nunarecorder.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.nunarecorder.lifelog.ActivityLabel
import com.example.nunarecorder.lifelog.AnnotationPrompt
import com.example.nunarecorder.lifelog.DiaryEntry
import com.example.nunarecorder.lifelog.LifelogUiState
import com.example.nunarecorder.lifelog.TimelineEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun LifelogScreen(
    state: LifelogUiState,
    onRefresh: () -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onAnnotate: (AnnotationPrompt, String, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var correcting by remember { mutableStateOf<AnnotationPrompt?>(null) }
    val labelsById = state.taxonomy.associateBy { it.id }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "生活记录",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "模型推断仅供参考，你可以确认、修正或跳过",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                } else {
                    TextButton(onClick = onRefresh) { Text("刷新") }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onPreviousDay) { Text("前一天") }
                Text(
                    "${state.date} UTC",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedButton(onClick = onNextDay) { Text("后一天") }
            }
        }

        item {
            Text(
                "当前试点契约按 UTC 日期查询和显示；服务端加入用户时区后再切换为本地日期。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        state.error?.let { error ->
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }

        if (state.pending.isNotEmpty()) {
            item {
                SectionTitle("待确认", "${state.pending.size} 条")
            }
            items(state.pending, key = { "prompt-${it.eventId}" }) { prompt ->
                AnnotationCard(
                    prompt = prompt,
                    onConfirm = { onAnnotate(prompt, "confirm", null) },
                    onCorrect = { correcting = prompt },
                    onSkip = { onAnnotate(prompt, "skip", null) }
                )
            }
        }

        if (state.diary.isNotEmpty()) {
            item {
                SectionTitle("当日日记", "${state.diary.size} 条")
            }
            items(
                state.diary,
                key = { "diary-${it.startTimeMs}-${it.endTimeMs}-${it.label}" }
            ) { entry ->
                DiaryCard(entry)
            }
        }

        item {
            SectionTitle("活动时间轴", "${state.timeline.size} 个片段")
        }

        if (!state.loading && state.timeline.isEmpty()) {
            item {
                Text(
                    "这一天还没有服务器处理完成的活动记录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            }
        }

        items(state.timeline, key = { "segment-${it.segmentId}" }) { entry ->
            TimelineCard(entry, labelsById)
        }

        item {
            state.lastUpdatedMs?.let {
                Text(
                    "最后更新 ${formatClock(it)} · Schema v${state.schemaVersion}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } ?: Spacer(Modifier.height(16.dp))
        }
    }

    correcting?.let { prompt ->
        CorrectLabelDialog(
            prompt = prompt,
            taxonomy = state.taxonomy,
            onDismiss = { correcting = null },
            onSubmit = { label ->
                correcting = null
                onAnnotate(prompt, "correct", label)
            }
        )
    }
}

@Composable
private fun DiaryCard(entry: DiaryEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${formatClock(entry.startTimeMs)}–${formatClock(entry.endTimeMs)}",
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    entry.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(entry.summary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AnnotationCard(
    prompt: AnnotationPrompt,
    onConfirm: () -> Unit,
    onCorrect: () -> Unit,
    onSkip: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(if (prompt.kind == "block") "活动变化" else "声音事件") }
                )
                prompt.startTimeMs?.let {
                    Text(formatClock(it), style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                prompt.question,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (prompt.asrText.isNotBlank()) {
                Text(
                    "“${prompt.asrText}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                    Text("正确")
                }
                OutlinedButton(onClick = onCorrect, modifier = Modifier.weight(1f)) {
                    Text("修改")
                }
                TextButton(onClick = onSkip) {
                    Text("不确定")
                }
            }
        }
    }
}

@Composable
private fun TimelineCard(
    entry: TimelineEntry,
    labelsById: Map<String, ActivityLabel>
) {
    val labelId = entry.effectiveLabel ?: "other"
    val displayName = labelsById[labelId]?.name ?: labelId
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${formatClock(entry.startTimeMs)}–${formatClock(entry.endTimeMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (entry.confirmedLabel != null) {
                Text(
                    "用户已确认",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (entry.predictedConfidence != null) {
                Text(
                    "${entry.predictedSource ?: "model"} · ${(entry.predictedConfidence * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (entry.asrText.isNotBlank()) {
                Text(
                    entry.asrText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (entry.soundEvents.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    entry.soundEvents.joinToString(" · ") {
                        "${it.name} ${(it.probability * 100).toInt()}%"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CorrectLabelDialog(
    prompt: AnnotationPrompt,
    taxonomy: List<ActivityLabel>,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var selected by remember(prompt.eventId) {
        mutableStateOf(prompt.suggestedLabel ?: taxonomy.firstOrNull()?.id.orEmpty())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("当时在做什么？") },
        text = {
            Column(
                modifier = Modifier
                    .height(360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                taxonomy.forEach { label ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == label.id,
                            onClick = { selected = label.id }
                        )
                        Text(label.name)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(selected) },
                enabled = selected.isNotBlank()
            ) {
                Text("提交")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun SectionTitle(title: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatClock(epochMs: Long): String =
    SimpleDateFormat("HH:mm 'UTC'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(epochMs))
