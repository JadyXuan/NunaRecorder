package com.example.nunarecorder.data

/**
 * 「这台设备的固件对不对」。
 *
 * 2026-08-14 实测：`4C:FF:01:A0:05:7A` 跑的是 `3.14.5.1736`，
 * **连续 51 分钟一个毫米波包都没有**，而 manifest 上除了 `device_firmware`
 * 没有任何地方看得出来。八台台账内设备都是 `3.14.5.1813`，都有毫米波。
 *
 * 危险的是**台账管不到的设备**：参与者自带机不会经过统一升级流程，
 * 它的毫米波会全程为空且没人察觉。
 *
 * ## 为什么这里只是"降级"，不是"阻断"
 *
 * 阻断意味着固件旧的参与者**一秒音频都采不到**。而毫米波是附加模态，
 * 音频才是任务本身（`AGENTS.md` §1，`SessionRecorder.feedSensorPacket` 也是这个原则）。
 * 为了保住一个附加模态而丢掉全部音频，是把优先级反过来了。
 *
 * ## 为什么判据只能用**存下来的**固件，不能现读
 *
 * 权威固件来自 A001 `0x05`，而 A001 **在收到第一帧音频之后才订阅**——
 * 175 个会话里 `3.14.5.1813` / `3.14.5.1736` 共 13 次，**全部有音频**。
 * 采集开始之前唯一读得到的是标准 DIS，实测每台设备都返回 `1.0.0`
 * （`01:8A` 这一台同时记录过 `1.0.0` 28 次和 `3.14.5.1813` 4 次），
 * **分不出 1813 和 1736**。
 *
 * 所以第一次用一台新设备是拦不住的，只能靠采集界面那条「这台设备没有在发毫米波」
 * 在三分钟内暴露；**从第二次起**才有这个判据。这是这条检查的真实能力边界，
 * 不要当成"每次开采都验过固件"。
 */
object DeviceFirmwarePolicy {

    /** 统一基线。八台台账内设备都是它 */
    const val EXPECTED = "3.14.5.1813"

    /** DIS 的通用串，不是真固件；见类文档 */
    const val DIS_PLACEHOLDER = "1.0.0"

    enum class Verdict {
        /** 还没采过，不知道 */
        UNKNOWN,
        MATCH,
        /** 已知且不是基线 */
        MISMATCH
    }

    /** 只有权威来源（A001 `0x05`）的值才值得存 */
    fun isTrustworthy(version: String?): Boolean =
        !version.isNullOrBlank() && version != DIS_PLACEHOLDER

    fun verdict(stored: String?, expected: String = EXPECTED): Verdict = when {
        !isTrustworthy(stored) -> Verdict.UNKNOWN
        stored == expected -> Verdict.MATCH
        else -> Verdict.MISMATCH
    }

    /** 给参与者看的一句话；[Verdict.UNKNOWN] 和 [Verdict.MATCH] 都不用说什么 */
    fun warning(stored: String?, expected: String = EXPECTED): String? =
        if (verdict(stored, expected) != Verdict.MISMATCH) null
        else "这台设备的固件是 $stored，不是统一的 $expected。" +
            "音频照常采集，但毫米波很可能全程为空——实测旧固件的设备连续 51 分钟一个包都没有。" +
            "请联系研究员升级固件或换一台。"
}
