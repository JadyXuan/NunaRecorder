package com.example.nunarecorder.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nunarecorder.data.PairedDevice
import com.example.nunarecorder.data.ScannedDevice
import com.example.nunarecorder.connection.RecorderConnectionPhase
import com.example.nunarecorder.connection.RecorderConnectionState
import com.example.nunarecorder.ble.DevicePowerAvailability
import com.example.nunarecorder.ble.DevicePowerState
import com.example.nunarecorder.ui.LiveRecordingUiStats
import com.example.nunarecorder.ui.theme.NunaSuccess

@Composable
fun MainScreen(
    logText: String,
    deviceList: List<ScannedDevice>,
    pairedDevices: List<PairedDevice>,
    selectedDeviceAddress: String?,
    connectionState: RecorderConnectionState,
    devicePowerState: DevicePowerState? = null,
    liveRecordingStats: LiveRecordingUiStats? = null,
    onDeviceClick: (ScannedDevice) -> Unit,
    onPairedDeviceClick: (PairedDevice) -> Unit,
    onScanClick: () -> Unit,
    onConnectClick: () -> Unit,
    onStartRecordingClick: () -> Unit,
    onStopRecordingClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val logScrollState = rememberScrollState()

    LaunchedEffect(logText) {
        logScrollState.animateScrollTo(logScrollState.maxValue)
    }

    val nunaDevices = deviceList.filter {
        it.name?.contains("nuna", ignoreCase = true) == true
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ConnectionStatusCard(
            connectionState = connectionState,
            devicePowerState = devicePowerState,
            liveStats = liveRecordingStats
        )

        FilledTonalButton(
            onClick = onScanClick,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            enabled = connectionState.canScanOrSelect
        ) {
            Text("扫描附近 Nuna 设备", fontWeight = FontWeight.Medium)
        }

        SectionLabel("附近的 Nuna 设备")
        DeviceListCard(
            emptyText = "（未发现 Nuna 设备，请先扫描）",
            items = nunaDevices.map { Pair(it.name ?: "(no name)", it.address) },
            selectedAddress = selectedDeviceAddress,
            enabled = connectionState.canScanOrSelect,
            onItemClick = { addr ->
                nunaDevices.find { it.address == addr }?.let { onDeviceClick(it) }
            }
        )

        SectionLabel("已配对设备")
        DeviceListCard(
            emptyText = "（暂无配对设备）",
            items = pairedDevices
                .sortedByDescending { it.lastConnectedTime }
                .map { Pair(it.name ?: "(no name)", it.address) },
            selectedAddress = selectedDeviceAddress,
            enabled = connectionState.canScanOrSelect,
            onItemClick = { addr ->
                pairedDevices.find { it.address == addr }?.let { onPairedDeviceClick(it) }
            }
        )

        ElevatedButton(
            onClick = onConnectClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(10.dp),
            enabled = selectedDeviceAddress != null && connectionState.canConnect
        ) {
            Text(
                when {
                    selectedDeviceAddress == null -> "请先在上方选择设备"
                    connectionState.canConnect -> "连接 + 握手"
                    else -> connectionState.title
                },
                fontWeight = FontWeight.SemiBold
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ElevatedButton(
                onClick = onStartRecordingClick,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.elevatedButtonColors(
                    containerColor = NunaSuccess,
                    contentColor = Color.White
                ),
                enabled = connectionState.canStartRecording
            ) {
                Icon(Icons.Outlined.PlayArrow, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    when (connectionState.phase) {
                        RecorderConnectionPhase.HANDSHAKING -> "等待握手"
                        RecorderConnectionPhase.STARTING_RECORDING -> "正在启动"
                        RecorderConnectionPhase.RECORDING,
                        RecorderConnectionPhase.AUDIO_STALLED -> "录制进行中"
                        else -> "开始录制"
                    },
                    fontWeight = FontWeight.Medium
                )
            }
            OutlinedButton(
                onClick = onStopRecordingClick,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                ),
                enabled = connectionState.canStopRecording
            ) {
                Icon(Icons.Outlined.Close, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("停止录制", fontWeight = FontWeight.Medium)
            }
        }

        SectionLabel("日志")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            SelectionContainer(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = logText.ifEmpty { "（暂无日志）" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                        .verticalScroll(logScrollState)
                )
            }
        }
    }
}

@Composable
private fun DeviceListCard(
    emptyText: String,
    items: List<Pair<String, String>>,
    selectedAddress: String?,
    enabled: Boolean,
    onItemClick: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (items.isEmpty()) 52.dp else (items.size * 52).coerceAtMost(156).dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    emptyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items, key = { it.second }) { (name, address) ->
                    DeviceRow(
                        name = name,
                        address = address,
                        selected = address == selectedAddress,
                        enabled = enabled,
                        onClick = { onItemClick(address) }
                    )
                    if (items.last().second != address) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            thickness = 0.5.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp
    )
}

@Composable
private fun DeviceRow(
    name: String,
    address: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val bgColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    } else {
        Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(enabled = enabled) { onClick() }
            .alpha(if (enabled) 1f else 0.55f)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = address,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }
        if (selected) {
            Text(
                "已选",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ConnectionStatusCard(
    connectionState: RecorderConnectionState,
    devicePowerState: DevicePowerState? = null,
    liveStats: LiveRecordingUiStats? = null
) {
    val isRecording = connectionState.isRecording
    val isError = connectionState.phase == RecorderConnectionPhase.ERROR ||
        connectionState.phase == RecorderConnectionPhase.AUDIO_STALLED
    val isActive = connectionState.phase != RecorderConnectionPhase.DISCONNECTED &&
        connectionState.phase != RecorderConnectionPhase.ERROR
    val dotColor by animateColorAsState(
        targetValue = when {
            isError -> MaterialTheme.colorScheme.error
            isRecording -> NunaSuccess
            connectionState.phase == RecorderConnectionPhase.READY -> NunaSuccess
            isActive -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outline
        },
        animationSpec = tween(600),
        label = "dotColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "ripple")
    val rippleScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rippleScale"
    )
    val rippleAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rippleAlpha"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                isRecording || connectionState.phase == RecorderConnectionPhase.READY ->
                    NunaSuccess.copy(alpha = 0.08f)
                isActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .scale(rippleScale)
                            .alpha(rippleAlpha)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = connectionState.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        isError -> MaterialTheme.colorScheme.error
                        isRecording || connectionState.phase == RecorderConnectionPhase.READY -> NunaSuccess
                        isActive -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    }
                )
                Text(
                    text = connectionState.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                if (connectionState.isConnected && devicePowerState != null) {
                    Text(
                        text = devicePowerSummary(devicePowerState),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (
                            devicePowerState.availability == DevicePowerAvailability.AVAILABLE
                        ) FontWeight.Medium else FontWeight.Normal,
                        color = when {
                            devicePowerState.percent != null && devicePowerState.percent <= 15 ->
                                MaterialTheme.colorScheme.error
                            devicePowerState.availability == DevicePowerAvailability.AVAILABLE ->
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        }
                    )
                }
                if (liveStats != null) {
                    val segHint = if (liveStats.closedSegmentCount > 0) {
                        "${liveStats.closedSegmentCount} 段已封口 · "
                    } else {
                        ""
                    }
                    Text(
                        text = "${liveStats.formatTotalBytes()} · ${segHint}${liveStats.blePacketCount} 包",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = NunaSuccess
                    )
                }
            }
        }
    }
}

private fun devicePowerSummary(state: DevicePowerState): String = when (state.availability) {
    DevicePowerAvailability.READING -> "电量：正在读取…"
    DevicePowerAvailability.UNSUPPORTED -> "电量：设备未提供"
    DevicePowerAvailability.AVAILABLE -> buildString {
        append("电量：")
        append(state.percent?.let { "$it%" } ?: "未知")
        state.voltageMv?.let { append(" · ${"%.2f".format(it / 1000.0)} V") }
        when {
            state.charging == true -> append(" · 充电中")
            state.usbPresent == true -> append(" · USB 供电")
        }
    }
}
