package com.example.nunarecorder.util

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock

/**
 * 功耗采样。佩戴者报告"手机发热、掉电快"，但没有数字就只能凭感觉改。
 *
 * 每次采样记四个量，都是相对上一次的**增量**，这样一眼能看出速率：
 *
 * - 手机电量百分比：直接的用户感受
 * - 本进程 CPU 时间：我们自己烧了多少 CPU（区别于系统或别的 App）
 * - 写入字节数：写放大是发热的主要来源之一，08-06 查出过 manifest 每秒重写
 * - 收到的音频包数：用来归一化——采得多耗得多是正常的
 *
 * 采样本身很轻（几次系统调用），周期是分钟级。
 */
class PowerProbe(private val context: Context) {

    private var lastWallMs = 0L
    private var lastCpuMs = 0L
    private var lastBattery = -1
    private var lastPackets = 0L

    /** 本进程累计占用的 CPU 毫秒。它和墙钟的比值就是平均 CPU 占用率。 */
    private fun processCpuMs(): Long = Process.getElapsedCpuTime()

    /**
     * 是否在充电。**没有它就分不出"掉电慢"和"边充边录"**——
     * 08-10 有 26 个采样窗口电量在上升，是回头看电量增量才发现的。
     */
    private fun isCharging(): Boolean =
        runCatching {
            (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).isCharging
        }.getOrDefault(false)

    /**
     * 屏幕是否亮着。
     *
     * 实测 08-09 白天 2.8%/小时、晚上 6.8%/小时，**同一个 App、同样的采集负载**。
     * 差额几乎肯定是手机自用，但那是**推断**——因为当时没记屏幕状态。
     * 而这个数会被用来回答"参与者的手机撑不撑得住一天""7 天要不要发充电宝"，
     * **用推断值去定预算是不行的**。
     *
     * 采样是瞬时的，但周期均匀（3 分钟），所以一段时间里"亮"的样本占比可以当
     * 亮屏时长占比用；一小时约 20 个样本，分辨率足够分开 2.8 和 6.8。
     */
    private fun screenOn(): Boolean =
        runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        }.getOrDefault(false)

    private fun batteryPercent(): Int =
        runCatching {
            (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(-1)

    fun reset() {
        lastWallMs = SystemClock.elapsedRealtime()
        lastCpuMs = processCpuMs()
        lastBattery = batteryPercent()
        lastPackets = 0L
    }

    /**
     * 采一次并写进诊断日志。
     *
     * @param packets 会话累计收到的 20ms 包数，用于归一化
     * @param sessionBytes 会话累计写入的音频字节数
     */
    fun sample(packets: Long, sessionBytes: Long) {
        val nowWall = SystemClock.elapsedRealtime()
        val nowCpu = processCpuMs()
        val nowBattery = batteryPercent()

        if (lastWallMs == 0L) {
            reset()
            return
        }
        val wallDelta = nowWall - lastWallMs
        if (wallDelta <= 0) return

        val cpuDelta = nowCpu - lastCpuMs
        val cpuPercent = 100.0 * cpuDelta / wallDelta
        val batteryDelta = if (lastBattery >= 0 && nowBattery >= 0) nowBattery - lastBattery else 0
        val packetDelta = packets - lastPackets
        // 每小时掉电百分比，比"掉了 2%"更容易横向比较
        val perHour = if (wallDelta > 0) batteryDelta * 3_600_000.0 / wallDelta else 0.0

        DiagnosticsLog.log(
            "Power",
            ("窗口 %.1f 分钟 · CPU %.1f%% · 手机电量 %d%%（%+d，约 %.1f%%/小时）" +
                " · 充电 %s · 屏幕 %s · 收包 %,d · 音频 %.1f MB")
                .format(
                    wallDelta / 60000.0,
                    cpuPercent,
                    nowBattery,
                    batteryDelta,
                    perHour,
                    if (isCharging()) "是" else "否",
                    if (screenOn()) "亮" else "灭",
                    packetDelta,
                    sessionBytes / 1048576.0
                )
        )

        lastWallMs = nowWall
        lastCpuMs = nowCpu
        lastBattery = nowBattery
        lastPackets = packets
    }
}
