package com.example.nunarecorder.data

import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionPaths
import com.example.nunarecorder.sync.SessionSyncStatus
import java.io.File

sealed class RecordingEntry {
    abstract val displayName: String
    abstract val sortKey: Long
    abstract val localPath: String

    data class Session(
        val dir: File,
        val manifest: SessionManifest
    ) : RecordingEntry() {
        override val displayName: String = dir.name
        override val sortKey: Long = manifest.startedAtMs
        override val localPath: String = dir.absolutePath
        val isRecordingActive: Boolean get() = manifest.recordingActive
        val vadComplete: Boolean get() = manifest.vad.status == "complete"
        val speechSegments: Int get() = manifest.vad.speechSegments
        val totalSegments: Int get() = manifest.segments.size
        val firstSegment: File? get() =
            manifest.segments.firstOrNull()?.let { File(dir, it.file) }

        /**
         * 下面两个**构造时读一次**，不是 `get()`。
         *
         * 它们原来是 `get()`，于是列表每渲染一行、每重组一次就读一次盘——
         * `syncStatus` 还要再解析一份 JSON。35 条会话时列表就卡住了
         * （用户 2026-08-09 实测），而明天一天会带回 700–900 条。
         * `pendingCount` 那句 `entries.count { it.syncStatus?.status != "synced" }`
         * 是在**组合期**跑的，等于每帧几十次文件读取。
         *
         * 代价是构造 [Session] 必须在 IO 线程上做——[RecordingEntryLoader] 负责这件事。
         */
        val hasContext: Boolean = SessionPaths.contextFile(dir).exists()
        val syncStatus: SessionSyncStatus? = SessionSyncStatus.load(dir)
    }

    data class LegacyOpus(
        val opusFile: File
    ) : RecordingEntry() {
        override val displayName: String = opusFile.name
        override val sortKey: Long = opusFile.lastModified()
        override val localPath: String = opusFile.absolutePath
        val hasContext: Boolean get() = File(
            opusFile.parentFile,
            "${opusFile.nameWithoutExtension}${SessionPaths.LEGACY_BIN_SUFFIX}"
        ).exists()
    }
}
