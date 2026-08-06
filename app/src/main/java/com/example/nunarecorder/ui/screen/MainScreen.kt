package com.example.nunarecorder.ui.screen

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nunarecorder.data.PairedDevice
import com.example.nunarecorder.data.ScannedDevice
import com.example.nunarecorder.recording.LinkPhase
import com.example.nunarecorder.recording.LinkStatus
import com.example.nunarecorder.ui.LiveRecordingUiStats
import com.example.nunarecorder.ui.theme.NunaSuccess
import kotlinx.coroutines.delay

@Composable
fun MainScreen(
    logText: String,
    deviceList: List<ScannedDevice>,
    pairedDevices: List<PairedDevice>,
    selectedDeviceAddress: String?,
    linkStatus: LinkStatus,
    liveRecordingStats: LiveRecordingUiStats? = null,
    onDeviceClick: (ScannedDevice) -> Unit,
    onPairedDeviceClick: (PairedDevice) -> Unit,
    onScanClick: () -> Unit,
    onStartRecordingClick: () -> Unit,
    onStopRecordingClick: () -> Unit,
    onExportLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val logScrollState = rememberScrollState()
    // 状态一律取自服务发布的 LinkStatus。以前这里是
    // `connectionStatus.contains("录制中")`——用字符串匹配推断状态，
    // 于是停止录制后文案没被改掉，按钮就永远停在"录制中"。
    val sessionActive = linkStatus.isSessionActive

    // 每秒重组一次，让"多久没收到数据"是活的
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sessionActive) {
        while (sessionActive) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

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
        LinkHealthPanel(
            linkStatus = linkStatus,
            liveStats = liveRecordingStats,
            nowMs = nowMs
        )

        FilledTonalButton(
            onClick = onScanClick,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
        ) {
            Text("扫描附近 Nuna 设备", fontWeight = FontWeight.Medium)
        }

        SectionLabel("附近的 Nuna 设备")
        DeviceListCard(
            emptyText = "（未发现 Nuna 设备，请先扫描）",
            items = nunaDevices.map { Pair(it.name ?: "(no name)", it.address) },
            selectedAddress = selectedDeviceAddress,
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
            onItemClick = { addr ->
                pairedDevices.find { it.address == addr }?.let { onPairedDeviceClick(it) }
            }
        )

        // 连接和录制合成一个动作：佩戴者不需要理解「先连接握手，再开始录制」，
        // 而且分两步意味着中间那一步失败时没人会发现。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ElevatedButton(
                onClick = onStartRecordingClick,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.elevatedButtonColors(
                    containerColor = NunaSuccess,
                    contentColor = Color.White
                ),
                enabled = selectedDeviceAddress != null && !sessionActive
            ) {
                Icon(Icons.Outlined.PlayArrow, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (selectedDeviceAddress == null) "请先选择设备" else "开始采集",
                    fontWeight = FontWeight.Medium
                )
            }
            OutlinedButton(
                onClick = onStopRecordingClick,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.error.copy(alpha = if (sessionActive) 0.5f else 0.15f)
                ),
                enabled = sessionActive
            ) {
                Icon(Icons.Outlined.Close, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("停止采集", fontWeight = FontWeight.Medium)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionLabel("日志")
            OutlinedButton(
                onClick = onExportLog,
                shape = RoundedCornerShape(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 2.dp
                ),
                modifier = Modifier.height(30.dp)
            ) {
                Text("导出日志", style = MaterialTheme.typography.labelSmall)
            }
        }
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
            .clickable { onClick() }
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

/**
 * 状态面板。要一眼能看到四件事：连接状态、录制状态、**采集链路是否健康**、上传状态。
 *
 * 第三项是重点。2026-07-31 实测里 UI 显示"录制中"的时候链路已经断了，
 * 佩戴者毫无察觉地白戴了半天。所以"最近一次收到数据"必须是显式的一行，
 * 而不是从"录制中"去推断。
 */
@Composable
private fun LinkHealthPanel(
    linkStatus: LinkStatus,
    liveStats: LiveRecordingUiStats?,
    nowMs: Long
) {
    val staleMs = liveStats?.staleForMs(nowMs)
    val streaming = linkStatus.isStreaming &&
        staleMs != null &&
        staleMs < LiveRecordingUiStats.STALE_THRESHOLD_MS
    val healthy = linkStatus.isStreaming && streaming

    // Android 的一次 GATT 连接尝试要 30 秒才超时，而退避只有 1–4 秒，
    // 所以重连过程中绝大部分时间 phase 是 CONNECTING。如果这时只显示"正在连接设备"，
    // 走出范围的佩戴者看到的和正常启动时一模一样，完全不知道链路已经断了——
    // 这正是 P1-11 要消除的那种"看起来没事"。
    val retrying = linkStatus.reconnectAttempt > 0
    val headline = when (linkStatus.phase) {
        LinkPhase.IDLE -> "未开始采集"
        LinkPhase.CONNECTING ->
            if (retrying) "链路中断，正在重连（第 ${linkStatus.reconnectAttempt} 次）"
            else "正在连接设备"
        LinkPhase.CONNECTED -> "已连接，正在握手"
        LinkPhase.RECONNECTING -> "链路中断，正在自动重连"
        LinkPhase.RECORDING -> if (streaming) "正在采集" else "已订阅，但没有收到数据"
    }

    val dotColor by animateColorAsState(
        targetValue = when {
            healthy -> NunaSuccess
            linkStatus.isSessionActive -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.outline
        },
        animationSpec = tween(600),
        label = "dotColor"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                healthy -> NunaSuccess.copy(alpha = 0.08f)
                linkStatus.isSessionActive -> MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = headline,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        healthy -> NunaSuccess
                        linkStatus.isSessionActive -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    }
                )
            }

            linkStatus.deviceName?.let {
                StatusLine("设备", "$it${linkStatus.deviceAddress?.let { a -> " · $a" } ?: ""}")
            }
            linkStatus.batteryPercent?.let { pct ->
                StatusLine("设备电量", "$pct%", emphasis = pct <= 20)
            }

            if (retrying) {
                StatusLine(
                    "重连",
                    buildString {
                        append("第 ${linkStatus.reconnectAttempt} 次")
                        if (linkStatus.phase == LinkPhase.RECONNECTING) {
                            append(" · ${linkStatus.nextRetryInMs / 1000} 秒后重试")
                        } else {
                            append(" · 正在尝试")
                        }
                        linkStatus.reason?.let { append(" · $it") }
                    },
                    emphasis = true
                )
            }

            if (liveStats != null) {
                StatusLine(
                    "链路",
                    when {
                        staleMs == null -> "尚未收到任何音频帧"
                        staleMs < LiveRecordingUiStats.STALE_THRESHOLD_MS ->
                            "正常 · 最近 ${staleMs / 1000} 秒前收到数据"
                        else -> "已 ${staleMs / 1000} 秒没有数据"
                    },
                    emphasis = staleMs == null || staleMs >= LiveRecordingUiStats.STALE_THRESHOLD_MS
                )
                StatusLine(
                    "完整度",
                    "%.0f%%（%,d / %,d 个 20ms 包）".format(
                        liveStats.completeness * 100,
                        liveStats.receivedPackets,
                        liveStats.expectedPackets
                    ),
                    emphasis = liveStats.expectedPackets > 0 && liveStats.completeness < 0.9f
                )
                StatusLine(
                    "会话",
                    "${liveStats.closedSegmentCount} 段 · ${liveStats.formatTotalBytes()}" +
                        if (liveStats.disconnectCount > 0) " · 断连 ${liveStats.disconnectCount} 次" else ""
                )
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, emphasis: Boolean = false) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.width(44.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
            color = if (emphasis) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}
