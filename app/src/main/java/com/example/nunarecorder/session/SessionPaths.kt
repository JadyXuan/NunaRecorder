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
    /** 诊断日志随会话一起上传；`labels/` 是服务端允许的顶层目录之一 */
    const val DIAGNOSTICS_LOG_FILE = "labels/diagnostics.log"
    const val AUDIO_DIR = "audio"
    const val SEGMENT_PREFIX = "seg_"
    const val LEGACY_OPUS_SUFFIX = ".opus"
    const val LEGACY_BIN_SUFFIX = ".bin"
    const val STREAM_OPUS_FILE = "audio/stream.opus"

    /** BLE MAC → 目录名片段，如 `4C:FF:01:A0:07:3C` → `4C_FF_01_A0_07_3C` */
    fun formatMacForDirName(mac: String?): String? {
        if (mac.isNullOrBlank()) return null
        return mac.replace(":", "_").uppercase()
    }

    /** 兼容旧目录：设备名 sanitize（如 `nuna_device_073C`） */
    fun formatDeviceNameForDirName(deviceName: String): String =
        deviceName.replace("[^a-zA-Z0-9_-]".toRegex(), "_").ifBlank { "unknown" }

    /**
     * 从会话目录名解析 BLE MAC（仅当 ID 段为 6 组十六进制时）。
     * 兼容 `nuna_4C_FF_01_A0_07_3C_173…`；设备名格式则返回 null。
     */
    fun macFromSessionDirName(dirName: String): String? {
        if (!dirName.startsWith("nuna_")) return null
        val body = dirName.removePrefix("nuna_")
        val parts = body.split("_")
        if (parts.size < 2) return null
        if (!parts.last().all { it.isDigit() }) return null
        val idParts = parts.dropLast(1)
        if (idParts.size != 6) return null
        if (!idParts.all { it.length == 2 && it.all { c -> c.isDigit() || c in 'A'..'F' || c in 'a'..'f' } }) {
            return null
        }
        return idParts.joinToString(":") { it.uppercase() }
    }

    /** App 自己的目录名，避免把几百个会话文件夹直接铺在 Download 根下 */
    const val APP_DIR_NAME = "NunaRecorder"

    /** 会话根目录：`Download/NunaRecorder/` */
    fun sessionsRoot(): File {
        val dir = File(legacyDownloadsRoot(), APP_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 旧版把会话直接建在 `Download/` 下。已有数据不搬动（搬运本身有丢数据的风险），
     * 列表时两个位置都扫，新会话一律建在 [sessionsRoot] 里。
     */
    fun legacyDownloadsRoot(): File {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    @Deprecated("新会话用 sessionsRoot()", ReplaceWith("sessionsRoot()"))
    fun downloadsRoot(): File = sessionsRoot()

    fun segmentFileName(index: Int): String =
        "${SEGMENT_PREFIX}${index.toString().padStart(3, '0')}$LEGACY_OPUS_SUFFIX"

    fun segmentRelativePath(index: Int): String = "$AUDIO_DIR/${segmentFileName(index)}"

    fun newSessionDir(deviceName: String, deviceAddress: String?, startedAtMs: Long = System.currentTimeMillis()): File {
        val idPart = formatMacForDirName(deviceAddress)
            ?: formatDeviceNameForDirName(deviceName)
        return File(sessionsRoot(), "nuna_${idPart}_$startedAtMs")
    }

    fun manifestFile(sessionDir: File): File = File(sessionDir, MANIFEST_FILE)

    fun contextFile(sessionDir: File): File = File(sessionDir, CONTEXT_FILE)

    fun vadPrelabelFile(sessionDir: File): File = File(sessionDir, VAD_PRELABEL_FILE)

    fun audioDir(sessionDir: File): File = File(sessionDir, AUDIO_DIR)

    fun isSessionDir(dir: File): Boolean =
        dir.isDirectory && manifestFile(dir).exists()

    /** 新旧两个位置都扫：升级前录的会话不能从列表里消失 */
    fun listSessionDirs(): List<File> =
        (listOf(sessionsRoot(), legacyDownloadsRoot())
            .flatMap { root -> root.listFiles { f -> f.isDirectory && isSessionDir(f) }?.toList() ?: emptyList() })
            .distinctBy { it.absolutePath }
            .sortedByDescending { manifestFile(it).lastModified() }

    fun listLegacyOpusFiles(): List<File> =
        legacyDownloadsRoot()
            .listFiles { f ->
                f.isFile &&
                    f.name.endsWith(LEGACY_OPUS_SUFFIX, ignoreCase = true) &&
                    !f.name.startsWith(SEGMENT_PREFIX) &&
                    !isSessionDir(File(f.parentFile, f.nameWithoutExtension))
            }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
}
