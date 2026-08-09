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
import com.example.nunarecorder.util.SystemInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.material3.TextButton

@Composable
fun SettingsScreen(
    userSettings: UserSettings,
    onLogLevelChange: (LogLevel) -> Unit,
    onAutoVadChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    /** Nuna 设备固件版本；读不到就传 null */
    deviceFirmware: String? = null,
    /** 复制系统信息到剪贴板 */
    onCopySystemInfo: (String) -> Unit = {},
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
            text = "录制与日志选项",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
        )

        Spacer(Modifier.height(20.dp))
        SettingsSectionLabel("录制")
        Spacer(Modifier.height(8.dp))

        SettingsCheckboxRow(
            checked = userSettings.autoVadOnRecord,
            onChecked = onAutoVadChange,
            label = "录制时自动 VAD 检测",
            subtitle = "关闭后可在录音列表中对已有数据单独执行 VAD"
        )
        Spacer(Modifier.height(12.dp))
        ReadOnlyRow(
            label = "分段时长",
            value = "60 秒（固定）",
            note = "标注模型与服务端入库都以 60 秒为最小单位，改成别的时长采到的数据无法入库。"
        )
        Spacer(Modifier.height(12.dp))
        ReadOnlyRow(
            label = "服务器配置",
            value = "来自入组码",
            note = "服务器地址、参与者编号和上传令牌都在入组二维码里，不在这里设置。要换服务器或换参与者，请到「入组」页重新扫码。"
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
        SettingsSectionLabel("系统信息")
        Spacer(Modifier.height(8.dp))
        SystemInfoCard(deviceFirmware = deviceFirmware, onCopy = onCopySystemInfo)
        Spacer(Modifier.height(24.dp))
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

/**
 * 排障时第一批要问的事实，直接摆出来，别让人手打。
 * 出问题时用户只要按"复制"发过来，就省掉一轮"你装的是哪个版本"。
 */
@Composable
private fun SystemInfoCard(deviceFirmware: String?, onCopy: (String) -> Unit) {
    val context = LocalContext.current
    val info = remember(deviceFirmware) { SystemInfo.read(context, deviceFirmware) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            InfoRow("App 版本", info.appVersion)
            InfoRow("系统版本", info.androidVersion)
            InfoRow("手机型号", info.phoneModel)
            InfoRow(
                "后台保活",
                if (info.ignoringBatteryOptimizations) "已豁免电池优化"
                else "未豁免——长时间采集可能被系统杀掉"
            )
            InfoRow("设备固件", info.deviceFirmware ?: "未知（连接后才读得到）")
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { onCopy(info.toShareText()) }) {
                Text("复制这些信息")
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.width(76.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

