package com.example.nunarecorder.util

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 进程内的诊断日志环形缓冲，可导出成一个文件发出去。
 *
 * 2026-07-31 实测里佩戴者报告"中间闪退过，重启两三次才能正常录制"，而我们手上没有任何日志，
 * 所以那次闪退到今天也不知道是什么。UI 上的日志区只有 120 行且随进程消失，
 * 排查不了 16 小时里发生的事。
 *
 * 只收结构化事件（BLE 连接/断开/重连、分段轮转、上传结果），不收音频内容、
 * 不收精确 GPS、不收令牌——导出的文件是要发给别人的。
 */
object DiagnosticsLog {

    private const val MAX_LINES = 5_000

    private val lines = ArrayDeque<String>()
    private val lock = Any()
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, message: String) {
        val line = "${stamp.format(Date())} [$tag] $message"
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    fun clear() {
        synchronized(lock) { lines.clear() }
    }

    fun lineCount(): Int = synchronized(lock) { lines.size }

    /** 导出内容：设备/构建信息 + 全部缓冲行。 */
    fun snapshot(): String {
        val header = buildString {
            appendLine("# EgoAudio Mobile Collector 诊断日志")
            appendLine("导出时间: ${stamp.format(Date())}")
            appendLine("机型: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("缓冲行数: ${lineCount()} / $MAX_LINES")
            appendLine()
        }
        val body = synchronized(lock) { lines.joinToString("\n") }
        return header + body + "\n"
    }

    /**
     * 写进 App 私有的 cache 目录，返回可通过 FileProvider 分享的文件。
     * 不写 Downloads：那里是会话数据，日志混进去会被当成采集产物。
     */
    fun exportTo(context: Context): File {
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val name = "egoaudio-log-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        val file = File(dir, name)
        file.writeText(snapshot())
        // 只保留最近 5 份，避免 cache 无限增长
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }
        return file
    }
}
