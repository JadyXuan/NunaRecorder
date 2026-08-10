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
        includeVad: Boolean
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
        if (includeContext) {
            add(SessionPaths.CONTEXT_FILE, "application/x-ndjson")
            // 毫米波数据和开关时间线跟着 context 一起上传。
            // 漏了这两条的后果是"采到了但传不上去"——那比没采还糟，
            // 因为手机上的副本迟早会被清理掉。add() 对不存在的文件是 no-op。
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
