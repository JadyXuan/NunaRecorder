package com.example.nunarecorder.vad

import com.example.nunarecorder.session.AudioSegmentEntry
import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionPaths
import java.io.File

object VadResumeHelper {

    /** manifest 存在且可解析 */
    fun hasValidManifest(sessionDir: File): Boolean =
        SessionManifest.load(SessionPaths.manifestFile(sessionDir)) != null

    /** 相对 manifest 中音频段，尚未成功完成 VAD 的段（含失败/缺失，可重试） */
    fun findPendingSegments(sessionDir: File): List<AudioSegmentEntry> {
        val manifest = SessionManifest.load(SessionPaths.manifestFile(sessionDir)) ?: return emptyList()
        val vad = VadPrelabelReader.load(sessionDir)
        val okIndices = vad?.segments
            ?.filter { it.status == "ok" }
            ?.map { it.index }
            ?.toSet()
            ?: emptySet()
        return manifest.segments.filter { seg ->
            val opus = File(sessionDir, seg.file)
            opus.exists() && opus.length() > 0L && seg.index !in okIndices
        }
    }

    fun isVadComplete(sessionDir: File): Boolean {
        val manifest = SessionManifest.load(SessionPaths.manifestFile(sessionDir)) ?: return false
        if (manifest.segments.isEmpty()) return true
        val pending = findPendingSegments(sessionDir)
        return pending.isEmpty()
    }

    fun vadStatusLabel(sessionDir: File): String {
        val manifest = SessionManifest.load(SessionPaths.manifestFile(sessionDir)) ?: return "unknown"
        if (isVadComplete(sessionDir)) return "complete"
        val pending = findPendingSegments(sessionDir)
        return if (pending.isNotEmpty()) "partial" else manifest.vad.status
    }

    /** App 启动时扫描所有未完成 VAD 的会话目录 */
    fun listSessionsNeedingVad(): List<File> =
        SessionPaths.listSessionDirs().filter { dir ->
            hasValidManifest(dir) && !isVadComplete(dir)
        }
}
