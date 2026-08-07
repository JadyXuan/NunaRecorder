package com.example.nunarecorder.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object BatteryOptimizationHelper {

    /**
     * OEM 的自启动 / 后台常驻白名单页面。
     *
     * 电池优化豁免是 AOSP 的标准开关，但 vivo / 华为 / 小米 / OPPO 各自还有一层
     * **自启动管理**，不在白名单里的应用会在划掉任务卡片或内存吃紧时被直接杀掉，
     * 前台服务也保不住。这一层没有公开 API，只能把用户送到那个页面。
     *
     * 这些 Activity 名是各家 ROM 的内部实现，随版本会变，所以逐个试、
     * 全都打不开就退回应用详情页——那里至少能找到"允许后台活动"。
     */
    private val AUTOSTART_TARGETS = listOf(
        // vivo（用户实测机型 V2303A）
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        // 华为
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
        // 小米
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        // OPPO / 一加
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        // 三星
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity"
    )

    /**
     * 尝试打开本机的自启动管理页面。
     * @return true 表示确实打开了某个页面（不代表用户开了开关）
     */
    fun openAutoStartSettings(context: Context): Boolean {
        for ((pkg, cls) in AUTOSTART_TARGETS) {
            val intent = Intent().apply {
                component = android.content.ComponentName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (context.packageManager.resolveActivity(intent, 0) != null) {
                runCatching { context.startActivity(intent) }.onSuccess { return true }
            }
        }
        // 退回应用详情页：那里能找到"允许后台活动"
        return runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + context.packageName)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
    }

    fun isIgnoringOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 打开系统「忽略电池优化」设置页（需用户手动允许） */
    fun requestIgnoreOptimizations(context: Context): Boolean {
        if (isIgnoringOptimizations(context)) return true
        return try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
