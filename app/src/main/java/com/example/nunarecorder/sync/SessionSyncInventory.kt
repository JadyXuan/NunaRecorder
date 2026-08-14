package com.example.nunarecorder.sync

import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionPaths
import java.io.File

object SessionSyncInventory {

    /** 生成相对会话目录的路径清单（含 SHA-256） */
    fun buildRelative(
        sessionDir: File,
        manifest: SessionManifest,
        includeContext: Boolean,
        includeVad: Boolean,
        /**
         * 毫米波原来跟着 [includeContext] 走，界面上也看不到它。
         * 参与者能单独取消上下文却不能单独取消毫米波，这不合理——它是另一类数据
         * （雷达感知），本来就该由参与者自己决定传不传。
         */
        includeMmWave: Boolean = true
    ): List<SyncFileEntry> {
        val list = mutableListOf<SyncFileEntry>()
        fun add(rel: String, media: String) {
            val f = File(sessionDir, rel)
            if (!f.isFile) return
            list.add(
                SyncFileEntry(
                    path = rel,
                    sha256 = SessionSyncStatus.sha256(f),
                    size = f.length(),
                    mediaType = media
                )
            )
        }
        add(SessionPaths.MANIFEST_FILE, "application/json")
        if (includeContext) add(SessionPaths.CONTEXT_FILE, "application/x-ndjson")
        if (includeMmWave) {
            // 数据和开关时间线必须一起走：只有数据没有开关时间线，事后做切片对齐时
            // 雷达开关那几帧音频缺失会被误判成掉线。add() 对不存在的文件是 no-op。
            add(SessionPaths.MMWAVE_FILE, "application/x-ndjson")
            add(SessionPaths.MMWAVE_STATE_FILE, "application/x-ndjson")
        }
        if (includeVad) add(SessionPaths.VAD_PRELABEL_FILE, "application/json")
        // 诊断日志随会话上传：排查断连、丢帧、上传失败最需要它，
        // 而指望佩戴者手工导出再发出来是不现实的。几十 KB，相对音频可忽略。
        add(SessionPaths.DIAGNOSTICS_LOG_FILE, "text/plain")
        manifest.segments.forEach { add(it.file, "audio/opus") }
        return list
    }
}
