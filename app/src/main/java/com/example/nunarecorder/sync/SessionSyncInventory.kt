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
        if (includeContext) add(SessionPaths.CONTEXT_FILE, "application/x-ndjson")
        if (includeVad) add(SessionPaths.VAD_PRELABEL_FILE, "application/json")
        manifest.segments.forEach { add(it.file, "audio/opus") }
        return list
    }
}
