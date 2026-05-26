package com.example.nunarecorder.data

import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionPaths
import com.example.nunarecorder.sync.SessionSyncStatus
import java.io.File

sealed class RecordingEntry {
    abstract val displayName: String
    abstract val sortKey: Long

    data class Session(
        val dir: File,
        val manifest: SessionManifest
    ) : RecordingEntry() {
        override val displayName: String = dir.name
        override val sortKey: Long = manifest.startedAtMs
        val hasContext: Boolean get() = SessionPaths.contextFile(dir).exists()
        val vadComplete: Boolean get() = manifest.vad.status == "complete"
        val speechSegments: Int get() = manifest.vad.speechSegments
        val totalSegments: Int get() = manifest.segments.size
        val firstSegment: File? get() =
            manifest.segments.firstOrNull()?.let { File(dir, it.file) }
        val syncStatus: SessionSyncStatus? get() = SessionSyncStatus.load(dir)
    }

    data class LegacyOpus(
        val opusFile: File
    ) : RecordingEntry() {
        override val displayName: String = opusFile.name
        override val sortKey: Long = opusFile.lastModified()
        val hasContext: Boolean get() = File(
            opusFile.parentFile,
            "${opusFile.nameWithoutExtension}${SessionPaths.LEGACY_BIN_SUFFIX}"
        ).exists()
    }
}
