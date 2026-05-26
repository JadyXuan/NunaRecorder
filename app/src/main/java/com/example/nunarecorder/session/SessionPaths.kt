package com.example.nunarecorder.session

import android.os.Environment
import java.io.File

/**
 * 会话目录路径与命名约定（`Downloads/nuna_{device}_{started_at_ms}/`）。
 *
 * Schema: [docs/SESSION_SYNC_PROTOCOL.md] (section 1).
 */
object SessionPaths {
    const val FORMAT_VERSION = 1
    const val SEGMENT_DURATION_MS = 60_000L
    const val MANIFEST_FILE = "manifest.json"
    const val CONTEXT_FILE = "context/context.jsonl"
    const val VAD_PRELABEL_FILE = "labels/vad_prelabel.json"
    const val AUDIO_DIR = "audio"
    const val SEGMENT_PREFIX = "seg_"
    const val LEGACY_OPUS_SUFFIX = ".opus"
    const val LEGACY_BIN_SUFFIX = ".bin"

    fun downloadsRoot(): File {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun segmentFileName(index: Int): String =
        "${SEGMENT_PREFIX}${index.toString().padStart(3, '0')}$LEGACY_OPUS_SUFFIX"

    fun segmentRelativePath(index: Int): String = "$AUDIO_DIR/${segmentFileName(index)}"

    fun newSessionDir(deviceName: String, startedAtMs: Long = System.currentTimeMillis()): File {
        val safe = deviceName.replace("[^a-zA-Z0-9_-]".toRegex(), "_").ifBlank { "unknown" }
        return File(downloadsRoot(), "nuna_${safe}_$startedAtMs")
    }

    fun manifestFile(sessionDir: File): File = File(sessionDir, MANIFEST_FILE)

    fun contextFile(sessionDir: File): File = File(sessionDir, CONTEXT_FILE)

    fun vadPrelabelFile(sessionDir: File): File = File(sessionDir, VAD_PRELABEL_FILE)

    fun audioDir(sessionDir: File): File = File(sessionDir, AUDIO_DIR)

    fun isSessionDir(dir: File): Boolean =
        dir.isDirectory && manifestFile(dir).exists()

    fun listSessionDirs(): List<File> =
        downloadsRoot()
            .listFiles { f -> f.isDirectory && isSessionDir(f) }
            ?.sortedByDescending { manifestFile(it).lastModified() }
            ?: emptyList()

    fun listLegacyOpusFiles(): List<File> =
        downloadsRoot()
            .listFiles { f ->
                f.isFile &&
                    f.name.endsWith(LEGACY_OPUS_SUFFIX, ignoreCase = true) &&
                    !f.name.startsWith(SEGMENT_PREFIX) &&
                    !isSessionDir(File(f.parentFile, f.nameWithoutExtension))
            }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
}
