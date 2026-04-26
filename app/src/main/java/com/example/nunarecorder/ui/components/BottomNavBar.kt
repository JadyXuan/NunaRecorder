package com.example.nunarecorder.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
fun BottomNavBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    data class TabItem(val icon: ImageVector, val label: String)

    val tabs = listOf(
        TabItem(Icons.Outlined.LocationOn, "设备"),
        TabItem(Icons.Outlined.List, "录音"),
        TabItem(Icons.Outlined.Settings, "设置")
    )

    NavigationBar(modifier = modifier) {
        tabs.forEachIndexed { index, tab ->
            NavigationBarItem(
                selected = selectedTab == index,
                onClick = { onTabSelected(index) },
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label
                    )
                },
                label = { Text(tab.label) }
            )
        }
        // DEBUG_WEARABLE_START — 已从主导航移除；恢复时取消注释
        /*
        NavigationBarItem(
            selected = selectedTab == 3,
            onClick = { onTabSelected(3) },
            icon = { Icon(Icons.Outlined.BugReport, contentDescription = "Wearable") },
            label = { Text("Wearable") }
        )
        */
        // DEBUG_WEARABLE_END
    }
}
