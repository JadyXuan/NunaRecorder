package com.example.nunarecorder.util

import android.content.Context
import com.example.nunarecorder.data.PairedDevice
import com.example.nunarecorder.enroll.EnrollmentCode
import com.example.nunarecorder.voiceprint.VoiceprintSession

/**
 * 第一次用这个 App 该先做什么。
 *
 * 参与者拿到手机时面对的是一个已经装好、但什么都没配的 App。研究员在场时能口头带一遍，
 * 可他一次要发好几台，而且**漏掉的那一步不会当场报错**——漏了入组就上传不了，
 * 漏了声纹就没有说话人参照，都是回来才发现。所以这些前置动作要由 App 自己盯着。
 *
 * 判据全部来自**可观察的状态**，不是"有没有弹过提示"的标记位：
 * 重装、清数据、换手机之后该提示的仍然会提示。
 */
object FirstRunGuide {

    enum class Step {
        /** 还没扫入组码：没有服务器地址、没有令牌，采了也传不上去 */
        ENROLL,
        /** 还没配过设备：没有设备就没有音频 */
        PAIR_DEVICE,
        /** 还没录声纹：说话人参照缺失，回来补不了当天的 */
        VOICEPRINT,
        /** 都齐了 */
        READY
    }

    data class Advice(val step: Step, val title: String, val body: String, val action: String)

    fun nextStep(
        enrollment: EnrollmentCode?,
        pairedDevices: List<PairedDevice>,
        hasVoiceprint: Boolean
    ): Step = when {
        enrollment == null -> Step.ENROLL
        pairedDevices.isEmpty() -> Step.PAIR_DEVICE
        !hasVoiceprint -> Step.VOICEPRINT
        else -> Step.READY
    }

    fun adviceFor(step: Step): Advice? = when (step) {
        Step.ENROLL -> Advice(
            step,
            "先扫入组码",
            "研究员会给你一张带二维码的卡片。扫过之后 App 才知道把录音传到哪里，" +
                "也才拿得到登录标注网站的凭据。",
            "去扫码"
        )
        Step.PAIR_DEVICE -> Advice(
            step,
            "配对你的 Nuna 设备",
            "把设备放回充电板，长按按键到白灯闪烁进入配对模式，再回来扫描。" +
                "只有第一次需要这么做，之后点开始采集就会自动连上。",
            "去扫描设备"
        )
        Step.VOICEPRINT -> Advice(
            step,
            "录两段声纹再出门",
            "用来在录音里认出哪一句是你自己说的。**只能在采集期间录**，" +
                "而且必须当天在场——回来之后补不了那一天的。",
            "去录声纹"
        )
        Step.READY -> null
    }

    /** 两段声纹都录过且不是空文件 */
    fun hasVoiceprint(context: Context): Boolean =
        VoiceprintSession.Step.entries.all { step ->
            VoiceprintSession.fileFor(context, step).let { it.exists() && it.length() > 0L }
        }
}
