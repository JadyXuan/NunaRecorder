package com.example.nunarecorder.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nunarecorder.data.LogLevel
import com.example.nunarecorder.data.UserSettings
import com.example.nunarecorder.sync.ServerHandshakeCheck

@Composable
fun SettingsScreen(
    userSettings: UserSettings,
    onUserIdChange: (String) -> Unit,
    onServerHostChange: (String) -> Unit,
    onServerPortChange: (String) -> Unit,
    onUploadTokenChange: (String) -> Unit,
    onLogLevelChange: (LogLevel) -> Unit,
    onAutoVadChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    /** null = 还没自检过；非 null = 上次保存后的服务器握手结果 */
    checkResult: ServerHandshakeCheck.Result? = null,
    checkRunning: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Text(
            text = "用户设置",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "上传、录制与日志选项",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
        )

        Spacer(Modifier.height(20.dp))
        SettingsSectionLabel("身份识别")
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = userSettings.userId,
            onValueChange = onUserIdChange,
            label = { Text("用户 ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )
        Text(
            "设备 MAC 在连接时自动写入每条录音的 manifest，上传时使用。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.padding(top = 6.dp)
        )

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        Spacer(Modifier.height(16.dp))

        SettingsSectionLabel("录制")
        Spacer(Modifier.height(8.dp))

        SettingsCheckboxRow(
            checked = userSettings.autoVadOnRecord,
            onChecked = onAutoVadChange,
            label = "录制时自动 VAD 检测",
            subtitle = "关闭后可在录音列表中对已有数据单独执行 VAD"
        )
        Spacer(Modifier.height(8.dp))
        Spacer(Modifier.height(12.dp))
        ReadOnlyRow(
            label = "分段时长",
            value = "60 秒（固定）",
            note = "标注模型与服务端入库都以 60 秒为最小单位，改成别的时长采到的数据无法入库。"
        )

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        Spacer(Modifier.height(16.dp))

        SettingsSectionLabel("上传服务器")
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = userSettings.serverHost,
            onValueChange = onServerHostChange,
            label = { Text("服务器地址") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = userSettings.serverPort.toString(),
            onValueChange = onServerPortChange,
            label = { Text("端口") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = userSettings.uploadToken,
            onValueChange = onUploadTokenChange,
            label = { Text("上传令牌") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )
        Text(
            text = "留空则服务端拒绝同步（Receiver 的 UPLOAD_TOKEN）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.padding(top = 4.dp, start = 4.dp)
        )

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        Spacer(Modifier.height(16.dp))

        SettingsSectionLabel("日志")
        Spacer(Modifier.height(8.dp))
        LogLevel.entries.forEach { level ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            ) {
                RadioButton(
                    selected = userSettings.logLevel == level,
                    onClick = { onLogLevelChange(level) }
                )
                Column {
                    Text(
                        when (level) {
                            LogLevel.INFO -> "普通"
                            LogLevel.DEBUG -> "调试"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        when (level) {
                            LogLevel.INFO -> "仅显示步骤与结果"
                            LogLevel.DEBUG -> "包含握手、BLE 等详细日志"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = onSave,
            enabled = !checkRunning,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text(
                if (checkRunning) "正在检查服务器…" else "保存并检查服务器",
                fontWeight = FontWeight.SemiBold
            )
        }

        if (checkResult != null) {
            Spacer(Modifier.height(12.dp))
            ServerCheckCard(checkResult)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 保存后的自检结果。参与者在入组现场就该看到能不能传，
 * 而不是采完一天才发现配置错了。
 */
@Composable
private fun ServerCheckCard(result: ServerHandshakeCheck.Result) {
    val headline = when {
        result.ok && !result.hasWarning -> "配置可用，可以开始采集"
        result.ok -> "基本可用，但有需要注意的项"
        else -> "配置有问题，现在还传不上去"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (result.ok) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            } else {
                MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
            }
        )
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                headline,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (result.ok) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            result.lines.forEach { line ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        when (line.level) {
                            ServerHandshakeCheck.Level.OK -> "\u2713"
                            ServerHandshakeCheck.Level.WARN -> "!"
                            ServerHandshakeCheck.Level.FAIL -> "\u2715"
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
                    Text(
                        line.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadOnlyRow(label: String, value: String, note: String) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(Modifier.width(10.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        Text(
            note,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun SettingsCheckboxRow(
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    label: String,
    subtitle: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Column(modifier = Modifier.padding(top = 10.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        fontWeight = FontWeight.SemiBold
    )
}
