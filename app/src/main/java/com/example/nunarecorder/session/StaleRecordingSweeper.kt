package com.example.nunarecorder.session

import java.io.File

/**
 * 把「上次没能正常收尾」的会话标记清掉。
 *
 * `manifest.recording.active = true` 只有 [com.example.nunarecorder.recording.SessionRecorder]
 * 正常收尾时才会被写回 false。进程被 OEM 杀掉、用户划掉任务卡、崩溃——都不会走到那一步，
 * 于是这个标记会**永久**留在 true。
 *
 * 后果比看起来严重：
 * - 「全部上传」按 `recordingActive` 跳过"正在录的"，于是这个会话**永远不会被批量上传**，
 *   只能手动一个个传。用户 2026-08-09 实测："有一个被漏掉的，是我最后上传的一个文件，
 *   这个文件必须手动上传，否则不被算到全部上传里面。"
 * - 界面上它一直显示"录制中"。
 * - 传到服务端的 manifest 也在撒谎——它声称那次采集还在进行。
 *
 * 采集一整天会有十几个会话，只要中途被杀过一次就漏一个，而漏掉的那个不会有任何提示。
 * 这正是本项目最在意的一类问题：**不是没实现，是实现了但会安静地把数据落下**。
 */
object StaleRecordingSweeper {

    data class Result(val cleared: List<String>)

    /**
     * @param activePath 此刻真正在录的会话目录；null 表示没有在录
     * @return 被清理的会话名
     */
    fun sweep(dirs: List<File>, activePath: String?): Result {
        val cleared = mutableListOf<String>()
        for (dir in dirs) {
            if (dir.absolutePath == activePath) continue
            val file = SessionPaths.manifestFile(dir)
            val m = SessionManifest.load(file) ?: continue
            if (!m.recordingActive) continue

            m.recordingActive = false
            m.openSegmentIndex = null
            m.openSegmentBytes = 0L
            // 没有正常收尾就没有 ended_at_ms。用最后一段的结束时间兜底，
            // 空会话则退回开始时间——**不要用"现在"**，那会把一次崩溃写成
            // 一段长达数小时的采集。
            if (m.endedAtMs == null) {
                m.endedAtMs = m.segments.maxOfOrNull { m.startedAtMs + it.endMs } ?: m.startedAtMs
            }
            runCatching { SessionManifestIO.write(dir, m) }
                .onSuccess { cleared.add(dir.name) }
        }
        return Result(cleared)
    }
}
