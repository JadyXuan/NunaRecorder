package com.example.nunarecorder.ble

/**
 * BLE 扫描限流。
 *
 * Android 从 7.0 起有一条**不抛异常、不回调、文档里也不写**的规则：
 * 同一个应用在 30 秒内启动扫描超过 5 次，系统就把它的扫描静默挡掉一段时间。
 * `startScan()` 照样返回，`onScanFailed` 在多数机型上**不会被调用**，
 * 结果就是「扫描中…」转个不停、一台设备都发现不了。
 *
 * 用户 2026-08-09 实测：「有的时候还会卡住，附近的 nuna 设备一台都没有，
 * 重启后才看得到。」这是这条规则最典型的表现——重启应用会重置计数。
 *
 * 与其让用户对着一个永远扫不到东西的界面按第六次，不如**在客户端就拦住**，
 * 并且告诉他还要等多久。这类"静默失败"在本项目里已经吃过太多亏。
 */
class ScanThrottle(
    private val maxStarts: Int = MAX_STARTS_PER_WINDOW,
    private val windowMs: Long = WINDOW_MS
) {

    private val starts = ArrayDeque<Long>()

    sealed class Decision {
        object Allowed : Decision()
        /** 需要再等 [waitMs] 毫秒；此刻扫也扫不到东西 */
        data class Throttled(val waitMs: Long) : Decision()
    }

    /**
     * 判断此刻能不能扫。返回 [Decision.Allowed] 时**已经把这次启动记进去了**，
     * 调用方必须真的去扫，否则计数会偏。
     */
    fun tryStart(nowMs: Long): Decision {
        while (starts.isNotEmpty() && nowMs - starts.first() >= windowMs) {
            starts.removeFirst()
        }
        if (starts.size < maxStarts) {
            starts.addLast(nowMs)
            return Decision.Allowed
        }
        // 最早那次滑出窗口时才腾得出名额
        val waitMs = windowMs - (nowMs - starts.first())
        return Decision.Throttled(waitMs.coerceAtLeast(1))
    }

    /** 蓝牙重开或应用重启后计数会被系统重置，本地也要跟着清 */
    fun reset() = starts.clear()

    companion object {
        /** Android 的阈值是 5 次 / 30 秒；这里留一次余量 */
        const val MAX_STARTS_PER_WINDOW = 4
        const val WINDOW_MS = 30_000L

        /** 把 [android.bluetooth.le.ScanCallback] 的错误码翻成能据此行动的一句话 */
        fun describeScanFailure(errorCode: Int): String = when (errorCode) {
            1 -> "已经有一个扫描在跑了（code=1）。等几秒再试。"
            2 -> "系统不肯再给这个应用开扫描（code=2）。" +
                "通常是之前的扫描没有被正确关掉。请把蓝牙关掉再打开；还不行就重启本应用。"
            3 -> "蓝牙内部错误（code=3）。把蓝牙关掉再打开。"
            4 -> "这台手机不支持所需的扫描方式（code=4）。"
            5 -> "系统资源不足（code=5）。关掉一些正在用蓝牙的应用再试。"
            6 -> "扫描太频繁，已被系统限流（code=6）。等 30 秒再试，不要连续点。"
            else -> "扫描失败（code=$errorCode）。"
        }
    }
}
