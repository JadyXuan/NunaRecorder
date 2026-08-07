package com.example.nunarecorder.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nunarecorder.voiceprint.VoiceprintQuality
import com.example.nunarecorder.voiceprint.VoiceprintSession

/**
 * 声纹录制向导。首次连上 Nuna 之后引导录两段。
 *
 * 两段都要：只录朗读的话，声学特征和真实对话差别太大，实际场景里配不上。
 *
 * 录完当场判定，不合格就地重录——参与者还在现场时重录几乎没有成本，
 * 事后发现声纹不可用的成本是这个人的数据永远做不了说话人分离。
 */
@Composable
fun VoiceprintScreen(
    state: VoiceprintSession.State,
    linkStreaming: Boolean,
    uploading: Boolean,
    uploadMessage: String?,
    onStart: (VoiceprintSession.Step) -> Unit,
    onStop: () -> Unit,
    onUpload: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("录制声纹", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "用你正在佩戴的 Nuna 设备录两段各约 30 秒。之后系统会用它自动区分录音里" +
                "哪些是你说的话，你不需要逐分钟去标注说话人。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        if (!linkStreaming) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
                )
            ) {
                Text(
                    "设备还没有在传音频。请先在「设备」页开始采集，等状态显示「正在采集」再回来录声纹——" +
                        "声纹必须用这台设备录，用手机麦克风录出来的和采集音频对不上。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }

        StepBlock(
            index = 1,
            title = "朗读下面这段文字",
            done = state.readDone,
            body = VoiceprintSession.READ_SCRIPT,
            active = state.active && state.step == VoiceprintSession.Step.READ,
            elapsedMs = state.elapsedMs,
            minMs = VoiceprintSession.minDurationFor(VoiceprintSession.Step.READ),
            enabled = linkStreaming && !state.active && !uploading,
            onStart = { onStart(VoiceprintSession.Step.READ) },
            onStop = onStop
        )

        StepBlock(
            index = 2,
            title = "用自己的话讲讲你的一天（至少 1 分钟，想讲多久都行）",
            done = state.freeDone,
            body = VoiceprintSession.FREE_PROMPT +
                "\n\n（用自然聊天的语气，不要念稿——朗读和聊天的声学特征差别很大。" +
                "讲满 1 分钟就可以停，但想多讲完全没问题。）",
            active = state.active && state.step == VoiceprintSession.Step.FREE,
            elapsedMs = state.elapsedMs,
            minMs = VoiceprintSession.minDurationFor(VoiceprintSession.Step.FREE),
            enabled = linkStreaming && !state.active && !uploading,
            onStart = { onStart(VoiceprintSession.Step.FREE) },
            onStop = onStop
        )

        state.lastResult?.let { r ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (r.ok) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                    else MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
                )
            ) {
                Text(
                    r.advice,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (r.ok) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }

        Button(
            onClick = onUpload,
            enabled = state.allDone && !uploading,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                when {
                    uploading -> "正在上传…"
                    state.allDone -> "上传声纹"
                    else -> "两段都录完才能上传"
                },
                fontWeight = FontWeight.SemiBold
            )
        }
        uploadMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("暂时跳过（之后可以再来录）")
        }
        Text(
            "声纹是生物特征信息，单独保存、不进入任何导出，你退出研究时会一并删除。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun StepBlock(
    index: Int,
    title: String,
    body: String,
    done: Boolean,
    active: Boolean,
    elapsedMs: Long,
    minMs: Long,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (done) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "$index. $title",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (done) {
                    Text(
                        "已完成",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            if (active) {
                // 进度条只画到"最低时长"为止；满了就变成满格，
                // 因为再往后没有上限——不该让参与者觉得该停了。
                val reached = elapsedMs >= minMs
                LinearProgressIndicator(
                    progress = { (elapsedMs.toFloat() / minMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    if (reached) {
                        "已录 %.0f 秒 · 时长已够，想继续讲就继续".format(elapsedMs / 1000.0)
                    } else {
                        "已录 %.0f 秒 / 至少 %d 秒".format(elapsedMs / 1000.0, minMs / 1000)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (reached) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(2.dp))
                Button(
                    onClick = onStop,
                    enabled = reached,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (reached) "讲完了，停止" else "至少还要 %d 秒".format(
                        ((minMs - elapsedMs) / 1000).coerceAtLeast(0)
                    ))
                }
            } else {
                OutlinedButton(
                    onClick = onStart,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (done) "重录这一段" else "开始录制") }
            }
        }
    }
}
