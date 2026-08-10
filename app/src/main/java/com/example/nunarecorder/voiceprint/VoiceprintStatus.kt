package com.example.nunarecorder.voiceprint

import android.content.Context
import java.io.File

/**
 * 「我到底录没录过声纹、什么时候录的、传上去了吗」。
 *
 * 用户 2026-08-09 原话：「录完了，也传完了，退出软件好像也不见了」。
 * 数据其实没丢（服务端 8 个文件都在），丢的是**本地状态**——但对参与者来说，
 * 「传完了，重启就没了」只有一个读法：没传上去。然后他会重录一遍，或者来问我们。
 * **让参与者觉得这东西不可靠，比真丢一段数据更贵。**
 *
 * 设计上尽量少存东西：
 * - **录没录过、什么时候录的、多长** —— 全部从声纹文件本身推导（存在性、
 *   `lastModified`、字节数）。文件才是事实，多存一份状态就多一处会漂开的记录。
 * - **传没传上去** —— 只有这一项没法从本地文件看出来，才落到 SharedPreferences。
 */
object VoiceprintStatus {

    private const val PREFS = "voiceprint_status"
    private const val KEY_UPLOADED_AT = "uploaded_at_"
    /** 记下上传时那份文件的大小，重录之后旧的"已上传"就不再算数 */
    private const val KEY_UPLOADED_SIZE = "uploaded_size_"

    data class StepStatus(
        val step: VoiceprintSession.Step,
        val recordedAtMs: Long?,
        val durationMs: Long,
        val bytes: Long,
        val uploadedAtMs: Long?
    ) {
        val recorded: Boolean get() = recordedAtMs != null && bytes > 0
        val uploaded: Boolean get() = uploadedAtMs != null
    }

    fun read(context: Context, step: VoiceprintSession.Step): StepStatus {
        val f = VoiceprintSession.fileFor(context, step)
        val bytes = if (f.isFile) f.length() else 0L
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uploadedSize = sp.getLong(KEY_UPLOADED_SIZE + step.name, -1L)
        val uploadedAt = sp.getLong(KEY_UPLOADED_AT + step.name, 0L).takeIf { it > 0L }
        return StepStatus(
            step = step,
            recordedAtMs = f.lastModified().takeIf { f.isFile && it > 0L },
            // 16 kHz / 2ch / 20 ms / 80 字节，与 SessionRecorder 用的是同一套量纲
            durationMs = bytes / 80 * 20,
            bytes = bytes,
            // 重录过（大小变了）就不能再说"已上传"，否则参与者会以为新的那份也传了
            uploadedAtMs = if (uploadedSize == bytes) uploadedAt else null
        )
    }

    fun readAll(context: Context): List<StepStatus> =
        VoiceprintSession.Step.entries.map { read(context, it) }

    fun markUploaded(context: Context, step: VoiceprintSession.Step) {
        val f = VoiceprintSession.fileFor(context, step)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_UPLOADED_AT + step.name, System.currentTimeMillis())
            .putLong(KEY_UPLOADED_SIZE + step.name, if (f.isFile) f.length() else 0L)
            .apply()
    }

    /** 两段都录了且都传了 */
    fun allUploaded(context: Context): Boolean = readAll(context).all { it.uploaded }

    /** 两段都录过（不论传没传） */
    fun allRecorded(context: Context): Boolean = readAll(context).all { it.recorded }
}
