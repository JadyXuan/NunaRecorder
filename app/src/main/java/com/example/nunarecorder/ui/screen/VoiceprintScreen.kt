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
    /** 每一段录没录过、什么时候录的、传没传上去 */
    statuses: List<com.example.nunarecorder.voiceprint.VoiceprintStatus.StepStatus> = emptyList(),
    modifier: Modifier = Modifier
) {
    // 界面上所有时长文案的唯一来源，避免和 VoiceprintQuality 里的常量漂开
    val freeMinLabel = formatDuration(VoiceprintSession.minDurationFor(VoiceprintSession.Step.FREE))
    val readMinLabel = formatDuration(VoiceprintSession.minDurationFor(VoiceprintSession.Step.READ))

    // **录没录过只有一个事实来源：磁盘上的声纹文件。**
    // 之前 `done` 取的是内存里的 state.readDone/freeDone，重启后是空的——
    // 于是状态行说"已录 08-09 · 还没上传"，上传按钮却是灰的，两句话互相打架
    // （用户 2026-08-10 原话："这不是自相矛盾吗"）。
    // state 只在本次刚录完时比文件更快一点，所以用 or 兜一下，不用它当依据。
    val readStatus = statuses.firstOrNull { it.step == VoiceprintSession.Step.READ }
    val freeStatus = statuses.firstOrNull { it.step == VoiceprintSession.Step.FREE }
    val readRecorded = readStatus?.recorded == true || state.readDone
    val freeRecorded = freeStatus?.recorded == true || state.freeDone
    val pendingUpload = listOfNotNull(readStatus, freeStatus).any { it.recorded && !it.uploaded }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("录制声纹", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "这两段只用来在录音里认出哪一句是你自己说的，**不会进入需要你标注的日常录音**——" +
            "录制期间日常采集会自动暂停，录完自动继续。\n\n" +
            "用你正在佩戴的 Nuna 设备录两段：一段朗读约 $readMinLabel，一段自己讲至少 $freeMinLabel" +
            "（想多讲随时可以，不封顶）。之后系统会用它自动区分录音里" +
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
            done = readRecorded,
            statusLine = readStatus?.let { describeStatus(it) },
            quality = state.lastResult.takeIf { state.lastStep == VoiceprintSession.Step.READ },
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
            // 时长一律从 MIN_FREE_MS 推导。写死的数字迟早和常量漂开——
            // 2026-08-09 用户就发现界面写"至少 1 分钟"、实际要求 2 分钟。
            title = "用自己的话讲讲你的一天（至少 $freeMinLabel，想讲多久都行）",
            done = freeRecorded,
            statusLine = freeStatus?.let { describeStatus(it) },
            quality = state.lastResult.takeIf { state.lastStep == VoiceprintSession.Step.FREE },
            body = VoiceprintSession.FREE_PROMPT +
                "\n\n（用自然聊天的语气，不要念稿——朗读和聊天的声学特征差别很大。" +
                "讲满 $freeMinLabel 就可以停，但想多讲完全没问题。）",
            active = state.active && state.step == VoiceprintSession.Step.FREE,
            elapsedMs = state.elapsedMs,
            minMs = VoiceprintSession.minDurationFor(VoiceprintSession.Step.FREE),
            enabled = linkStreaming && !state.active && !uploading,
            onStart = { onStart(VoiceprintSession.Step.FREE) },
            onStop = onStop
        )

        Button(
            onClick = onUpload,
            // 有"录了但还没传"的就能传。原来要求两段都录完，于是重启后
            // 状态行说"还没上传"、按钮却是灰的。只录了一段也该允许先把它传上去——
            // 传上去的那一份就安全了，剩下一段回头再补。
            enabled = pendingUpload && !uploading,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                when {
                    uploading -> "正在上传…"
                    pendingUpload && !(readRecorded && freeRecorded) -> "上传已录的这一段"
                    pendingUpload -> "上传声纹"
                    readRecorded && freeRecorded -> "两段都已上传"
                    else -> "先录一段再上传"
                },
                fontWeight = FontWeight.SemiBold
            )
        }
        uploadMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        // 两段都录过之后这个按钮就没意义了——它是给"还没开始"的人的出口，
        // 而录完的人需要的是"回主页"。用户 2026-08-10：「暂时跳过可能不需要再显示」。
        if (!(readRecorded && freeRecorded)) {
            OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text("暂时跳过（之后可以再来录）")
            }
        } else {
            OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text("返回主页")
            }
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
    /** 「已录 / 未录 / 已上传」那一行；null = 不显示 */
    statusLine: String? = null,
    /** 本次刚录完这一段的质量结论；null = 这一段没有刚录过 */
    quality: com.example.nunarecorder.voiceprint.VoiceprintQuality.Result? = null,
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
            statusLine?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "$index. $title",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                // 只有在没有状态行时才显示这个角标。状态行说的是同一件事而且更全
                // （"已录 08-10 14:23 · 2 分 5 秒 · 已上传"），两条同色的话
                // 界面上就是"多显示一个蓝色的已完成"——用户 2026-08-10 实测。
                if (done && statusLine == null) {
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
                ) { Text(if (done) "重新录制这一段" else "开始录制") }
            }
            // 质量结论跟着**它自己那一段**走。原来只在页面底部显示最近一次结果，
            // 于是录完两段只看得到第 2 段合不合格（用户 2026-08-10 实测）。
            quality?.let { r ->
                Text(
                    r.advice,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (r.ok) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}


/** 60 秒以下按秒说，以上按分钟说；整分不带小数 */
private fun formatDuration(ms: Long): String {
    val sec = ms / 1000
    if (sec < 60) return "$sec 秒"
    return if (sec % 60 == 0L) "${sec / 60} 分钟" else "%.1f 分钟".format(sec / 60.0)
}


/**
 * 「录没录过、什么时候录的、传上去了吗」——这一行是这次改动的全部意义。
 *
 * 用户 2026-08-09：「录完了，也传完了，退出软件好像也不见了」。数据其实没丢，
 * 丢的是本地状态；但对参与者来说"传完了、重启就没了"只有一个读法：没传上去。
 * 然后他会重录一遍，或者来问我们。
 */
private fun describeStatus(
    st: com.example.nunarecorder.voiceprint.VoiceprintStatus.StepStatus
): String {
    if (!st.recorded) return "还没有录过"
    val when_ = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(st.recordedAtMs!!))
    val len = formatDuration(st.durationMs)
    return if (st.uploaded) "已录 $when_ · $len · 已上传"
    else "已录 $when_ · $len · **还没上传**"
}
