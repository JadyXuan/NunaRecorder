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
import androidx.compose.material3.ButtonDefaults
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

@Composable
fun SettingsScreen(
    userSettings: UserSettings,
    onUserIdChange: (String) -> Unit,
    onServerHostChange: (String) -> Unit,
    onServerPortChange: (String) -> Unit,
    onUploadTokenChange: (String) -> Unit,
    onLogLevelChange: (LogLevel) -> Unit,
    onAutoVadChange: (Boolean) -> Unit,
    onSegmentEnabledChange: (Boolean) -> Unit,
    onSegmentDurationChange: (String) -> Unit,
    onSave: () -> Unit,
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
        SettingsCheckboxRow(
            checked = userSettings.segmentEnabled,
            onChecked = onSegmentEnabledChange,
            label = "录制时自动切片",
            subtitle = "关闭则整段保存为单个 Opus 文件"
        )
        if (userSettings.segmentEnabled) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = userSettings.segmentDurationSec.toString(),
                onValueChange = onSegmentDurationChange,
                label = { Text("切片时长（秒，10–600）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )
        }

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
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("保存设置", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(24.dp))
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
