package com.example.nunarecorder.util

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.ActivityCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * 开采前的环境自检。
 *
 * 2026-07-31 实测：29 分钟的会话里 activity 只有 2 条、GPS 0 条。事后没人说得清
 * 是权限没给、是机型没有 Google 服务、还是代码坏了——因为入组时没人记录过。
 * 缺一个权限不会让 App 崩溃，只会让某个模态**静默缺失**，等数据回来才发现，
 * 而那时参与者已经走了。
 *
 * 所以这里把"能不能采全"在开始之前就摆出来，并且区分**阻断**和**降级**：
 * 缺蓝牙权限是采不了，缺身体活动只是少一个模态。
 */
object CollectionReadiness {

    enum class Level {
        /** 采不了，必须解决 */
        BLOCKING,

        /** 能采，但会缺模态或长时不稳 */
        DEGRADED,

        /** 正常 */
        OK
    }

    data class Item(
        val level: Level,
        val title: String,
        /** 说清"不解决会怎样"，而不是只说"缺少 X 权限" */
        val consequence: String,
        /** 非空时界面给一个跳转按钮 */
        val actionHint: String? = null
    )

    data class Report(val items: List<Item>) {
        val blocking: List<Item> get() = items.filter { it.level == Level.BLOCKING }
        val degraded: List<Item> get() = items.filter { it.level == Level.DEGRADED }
        val canRecord: Boolean get() = blocking.isEmpty()
        val allGood: Boolean get() = items.none { it.level != Level.OK }

        /** 写进 manifest / 诊断日志的一行摘要 */
        fun summary(): String = when {
            allGood -> "环境自检全部通过"
            !canRecord -> "无法开始采集：" + blocking.joinToString("；") { it.title }
            else -> "可以采集，但会有缺失：" + degraded.joinToString("；") { it.title }
        }
    }

    private fun granted(context: Context, permission: String): Boolean =
        ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun check(context: Context): Report {
        val items = mutableListOf<Item>()

        // ── 阻断项 ────────────────────────────────────────────────────────
        val btPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
        items.add(
            if (granted(context, btPermission)) {
                Item(Level.OK, "蓝牙连接权限", "已授予")
            } else {
                Item(
                    Level.BLOCKING, "缺少蓝牙连接权限",
                    "完全无法连接 Nuna 设备，一秒音频都采不到。",
                    "到系统设置里给本应用授予「附近的设备」权限"
                )
            }
        )

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        items.add(
            when {
                adapter == null -> Item(Level.BLOCKING, "这台手机没有蓝牙", "无法采集。", null)
                !adapter.isEnabled -> Item(
                    Level.BLOCKING, "蓝牙未开启",
                    "无法连接设备。开始采集后再关蓝牙会中断链路。",
                    "下拉通知栏打开蓝牙"
                )
                else -> Item(Level.OK, "蓝牙已开启", "正常")
            }
        )

        // ── 降级项：不挡住采集，但会静默少东西 ──────────────────────────
        val fineLocation = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        items.add(
            when {
                fineLocation -> Item(Level.OK, "精确位置权限", "GPS 轨迹会被采集")
                granted(context, Manifest.permission.ACCESS_COARSE_LOCATION) -> Item(
                    Level.DEGRADED, "只有大致位置权限",
                    "GPS 精度不足，位置模态基本不可用。",
                    "在权限里把位置改成「精确」"
                )
                else -> Item(
                    Level.DEGRADED, "缺少位置权限",
                    "整段采集不会有任何 GPS 数据，事后无法还原去过哪里。",
                    "到系统设置里授予位置权限并选择「精确」"
                )
            }
        )

        // 系统定位模式。权限给足了也没用——系统定位若设成「省电/仅网络定位」，
        // GPS provider 整个是关的，只能拿到 Wi-Fi/基站定位。
        // 2026-08-09 全天采到的 provider 清一色 network、精度 47 米，
        // 而自检里**当时没有这一项**，于是出门前看不出这一路是断的。
        // 同意书 §3 承诺「每 30 秒记录一次 GPS」，这不是可选项。
        val gpsProviderOn = runCatching {
            (context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager)
                .isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        if (fineLocation) {
            items.add(
                if (gpsProviderOn) {
                    Item(Level.OK, "系统卫星定位已开启", "能拿到真实 GPS 轨迹")
                } else {
                    Item(
                        Level.DEGRADED, "系统关闭了卫星定位",
                        "只能拿到 Wi-Fi/基站定位（精度几十米），户外常常一个点都没有。" +
                            "同意书里承诺的每 30 秒一个 GPS 点做不到。",
                        "到系统「设置 → 位置」里把定位模式改成「高精度」"
                    )
                }
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            items.add(
                if (granted(context, Manifest.permission.ACTIVITY_RECOGNITION)) {
                    Item(Level.OK, "身体活动权限", "会记录走路/静止等状态")
                } else {
                    Item(
                        Level.DEGRADED, "缺少身体活动权限",
                        "不会有走路/静止/乘车这一层标签。",
                        "到系统设置里授予「身体活动」权限"
                    )
                }
            )
        }

        // 活动识别依赖 Google Play 服务，不是 AOSP 原生。
        // 2026-07-31 实测 29 分钟只采到 2 条 activity，当时无从判断是权限还是 ROM。
        val gms = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        }.getOrDefault(ConnectionResult.SERVICE_MISSING)
        items.add(
            if (gms == ConnectionResult.SUCCESS) {
                Item(Level.OK, "Google Play 服务", "活动识别可用")
            } else {
                Item(
                    Level.DEGRADED, "这台手机没有可用的 Google Play 服务",
                    "身体活动模态采不到，这是机型限制，不是权限问题。入组时应记录下来。",
                    null
                )
            }
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            items.add(
                if (granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
                    Item(Level.OK, "通知权限", "能看到采集状态和低电量提醒")
                } else {
                    Item(
                        Level.DEGRADED, "缺少通知权限",
                        "看不到采集状态常驻通知，也收不到设备低电量提醒；" +
                            "部分系统还会更快回收没有可见通知的后台服务。",
                        "到系统设置里允许本应用发送通知"
                    )
                }
            )
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoringBattery = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        items.add(
            if (ignoringBattery) {
                Item(Level.OK, "电池优化已关闭", "长时间采集不会被系统掐断")
            } else {
                Item(
                    Level.DEGRADED, "电池优化仍然开启",
                    "连续采集几小时后系统可能限制后台，导致采集中断且不易察觉。" +
                        "16 小时佩戴场景下这一条几乎一定会踩到。",
                    "点这里去关闭电池优化"
                )
            }
        )

        // 自启动白名单是 OEM 私有的一层，没有 API 可以查询是否已加入，
        // 所以只能无条件提醒。不提醒的代价是：前台服务在 vivo / 华为 / 小米上
        // 仍然会被划掉任务卡片时连带杀死，而佩戴者要过很久才发现采集停了。
        items.add(
            Item(
                Level.DEGRADED, "确认已允许后台自启动",
                "这一层是手机厂商自己的白名单，系统不提供查询接口，所以无法自动确认。" +
                    "不加进去的话，划掉任务卡片或内存紧张时采集会被直接杀掉。",
                "点这里去设置自启动"
            )
        )

        return Report(items)
    }
}
