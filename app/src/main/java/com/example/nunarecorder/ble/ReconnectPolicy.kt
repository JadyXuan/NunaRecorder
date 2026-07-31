package com.example.nunarecorder.ble

/**
 * 重连退避。指数增长到上限后一直重试，**没有次数上限**——
 * 佩戴者要连续戴 16 小时，任何"重试 N 次后放弃"都等于丢掉当天剩下的全部数据，
 * 而且佩戴者不会察觉。只有用户主动停止才结束。
 *
 * 不加随机抖动：这里只有一个 BLE 对端，抖动解决的是惊群问题，这里没有。
 * 确定性的退避序列反而更容易在日志里对照。
 */
class ReconnectPolicy(
    private val initialDelayMs: Long = 1_000L,
    private val maxDelayMs: Long = 30_000L,
    private val multiplier: Double = 2.0
) {
    /**
     * @param attempt 第几次重试，从 1 开始
     * @return 本次重试前应等待的毫秒数
     */
    fun delayForAttempt(attempt: Int): Long {
        if (attempt <= 1) return initialDelayMs
        var delay = initialDelayMs.toDouble()
        repeat(attempt - 1) {
            delay *= multiplier
            if (delay >= maxDelayMs) return maxDelayMs
        }
        return delay.toLong().coerceAtMost(maxDelayMs)
    }
}
