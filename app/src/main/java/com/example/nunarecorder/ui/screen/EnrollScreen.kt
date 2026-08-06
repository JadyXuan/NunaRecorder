package com.example.nunarecorder.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nunarecorder.enroll.EnrollmentCode
import com.example.nunarecorder.enroll.EnrollmentCodec
import com.example.nunarecorder.enroll.EnrollmentParseResult
import com.example.nunarecorder.sync.ServerHandshakeCheck

/**
 * 入组页：扫二维码或粘贴文本码，一次配置好地址、编号和令牌。
 *
 * 参与者全程不手输任何标识——这是 07-31 和 08-03 两次配置事故的直接对策。
 */
@Composable
fun EnrollScreen(
    current: EnrollmentCode?,
    enrolledAtMs: Long?,
    tokenRevoked: Boolean,
    checkResult: ServerHandshakeCheck.Result?,
    checkRunning: Boolean,
    onScanClick: () -> Unit,
    onCodeEntered: (String) -> Unit,
    onRecheck: () -> Unit,
    onClearEnrollment: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pasted by remember { mutableStateOf("") }
    var parseError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("入组配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        if (current == null) {
            Text(
                "还没有配置。请扫描研究员提供的入组二维码——地址、编号和令牌都在里面，" +
                    "不需要手动输入任何内容。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        } else {
            EnrolledCard(current, enrolledAtMs, tokenRevoked)
        }

        Button(
            onClick = onScanClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                if (current == null) "扫描入组二维码" else "重新扫描（换卡 / 换手机）",
                fontWeight = FontWeight.SemiBold
            )
        }

        Text(
            "二维码扫不出来时，可以让研究员把卡片下方的文本码发给你，粘贴到这里：",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        OutlinedTextField(
            value = pasted,
            onValueChange = { pasted = it; parseError = null },
            label = { Text("入组码（EGOAUDIO1:…）") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            isError = parseError != null,
            supportingText = parseError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } }
        )
        OutlinedButton(
            onClick = {
                when (val r = EnrollmentCodec.parse(pasted)) {
                    is EnrollmentParseResult.Ok -> {
                        parseError = null
                        onCodeEntered(pasted)
                        pasted = ""
                    }
                    // 具体原因当场给出，而不是"格式错误"——研究员要据此判断是重扫还是换卡
                    is EnrollmentParseResult.Error -> parseError = r.reason
                }
            },
            enabled = pasted.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("使用这个入组码") }

        if (current != null) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRecheck,
                    enabled = !checkRunning,
                    modifier = Modifier.weight(1f)
                ) { Text(if (checkRunning) "检查中…" else "重新检查服务器") }
                OutlinedButton(
                    onClick = onClearEnrollment,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("清除配置", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        checkResult?.let { ServerCheckSummary(it) }
    }
}

@Composable
private fun EnrolledCard(code: EnrollmentCode, enrolledAtMs: Long?, revoked: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (revoked) MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
        )
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (revoked) {
                Text(
                    "这台设备的上传令牌已被服务器撤销",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    "已录制的数据仍然保存在手机上，不会丢失。请联系研究员重新入组后再上传。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(4.dp))
            }
            InfoRow("参与者", code.participantId)
            InfoRow("上传服务器", code.serverUrl)
            // 令牌只显示前 4 位：足够研究员核对是哪张卡，又不会被截图泄露
            InfoRow("令牌", "${code.tokenFingerprint}…（已隐藏）")
            code.webUrl?.let { InfoRow("标注网站", it) }
            enrolledAtMs?.let {
                InfoRow(
                    "入组时间",
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(it))
                )
            }
        }
    }
}

/** 标签在上、值在下：服务器 URL 很长，并排会被挤断或换行到看不清 */
@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        )
        Text(value, style = MaterialTheme.typography.bodySmall, softWrap = true)
    }
}

@Composable
private fun ServerCheckSummary(result: ServerHandshakeCheck.Result) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (result.ok) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
        )
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (result.ok) "配置可用，可以开始采集" else "配置有问题，现在还传不上去",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (result.ok) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            result.lines.forEach { line ->
                Row {
                    Text(
                        when (line.level) {
                            ServerHandshakeCheck.Level.OK -> "✓"
                            ServerHandshakeCheck.Level.WARN -> "!"
                            ServerHandshakeCheck.Level.FAIL -> "✕"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = when (line.level) {
                            ServerHandshakeCheck.Level.OK -> MaterialTheme.colorScheme.primary
                            ServerHandshakeCheck.Level.WARN -> MaterialTheme.colorScheme.onSurface
                            ServerHandshakeCheck.Level.FAIL -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.width(18.dp)
                    )
                    Text(line.text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
