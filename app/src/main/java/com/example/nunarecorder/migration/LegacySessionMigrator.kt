package com.example.nunarecorder.migration

import com.example.nunarecorder.session.AudioSegmentEntry
import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionManifestIO
import com.example.nunarecorder.session.SessionPaths
import com.example.nunarecorder.session.VadSummary
import android.util.Log
import com.example.nunarecorder.vad.VadJobQueue
import com.example.nunarecorder.vad.VadResumeHelper
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * LEGACY_MIGRATION：将单文件 .opus [+ .bin] 转为会话文件夹 + 1 分钟分段，并触发 VAD 后处理队列。
 */
object LegacySessionMigrator {

    private const val TAG = "LegacySessionMigrator"
    private const val FRAME_BYTES = 80
    private const val FRAMES_PER_SEGMENT = 3000 // 60s @ 20ms/frame

    enum class ProgressPhase { SPLITTING, VAD_ONLY, FINISHING }

    data class Result(
        val sessionDir: File,
        val segmentCount: Int,
        val message: String
    )

    fun migrate(
        legacyOpus: File,
        onLog: (String) -> Unit = {},
        onProgress: (phase: ProgressPhase, current: Int, total: Int) -> Unit = { _, _, _ -> }
    ): Result {
        require(legacyOpus.isFile && legacyOpus.name.endsWith(SessionPaths.LEGACY_OPUS_SUFFIX, ignoreCase = true)) {
            "Not a legacy opus file"
        }
        val base = legacyOpus.nameWithoutExtension
        val legacyBin = File(legacyOpus.parentFile, "$base${SessionPaths.LEGACY_BIN_SUFFIX}")
        val startedAtMs = legacyOpus.name.substringAfterLast('_').toLongOrNull()
            ?: legacyOpus.lastModified()
        val devicePart = base.removePrefix("nuna_").substringBeforeLast("_")
        val sessionDir = File(legacyOpus.parentFile, base)

        // 已有完整迁移结果：仅续传未完成的 VAD，不删文件夹、不重新切分
        if (sessionDir.exists() && VadResumeHelper.hasValidManifest(sessionDir)) {
            val manifest = SessionManifest.load(SessionPaths.manifestFile(sessionDir))!!
            if (manifest.sourceOpus == legacyOpus.name) {
                if (VadResumeHelper.isVadComplete(sessionDir)) {
                    onLog("已迁移且 VAD 已完成: ${sessionDir.name}")
                    onProgress(ProgressPhase.VAD_ONLY, 1, 1)
                    return Result(sessionDir, manifest.segments.size, "已完成，无需重复操作")
                }
                onProgress(ProgressPhase.VAD_ONLY, 0, 1)
                val n = VadJobQueue.enqueuePendingSegments(sessionDir)
                onLog("续传 VAD: ${sessionDir.name}，剩余 $n 段")
                onProgress(ProgressPhase.VAD_ONLY, 1, 1)
                return Result(sessionDir, manifest.segments.size, "续传 VAD: $n 段待分析")
            }
        }

        // 无 manifest 的半成品目录（切分中断）或来源不一致：删掉重做
        if (sessionDir.exists()) {
            onLog("清理不完整会话目录: ${sessionDir.name}")
            sessionDir.deleteRecursively()
        }
        sessionDir.mkdirs()
        SessionPaths.audioDir(sessionDir).mkdirs()
        File(sessionDir, "context").mkdirs()
        File(sessionDir, "labels").mkdirs()

        if (legacyBin.exists()) {
            legacyBin.copyTo(SessionPaths.contextFile(sessionDir), overwrite = true)
        } else {
            SessionPaths.contextFile(sessionDir).writeText(
                """{"type":"meta","migrated":true,"source":"${legacyOpus.name}","started_at_ms":$startedAtMs}""" + "\n"
            )
        }

        Log.d(TAG, "split start: ${legacyOpus.name} bytes=${legacyOpus.length()}")
        val segments = splitOpusIntoSegments(legacyOpus, sessionDir, onLog, onProgress)
        Log.d(TAG, "split done: ${segments.size} segments")
        onProgress(ProgressPhase.FINISHING, 1, 1)
        val manifest = SessionManifest(
            sessionId = sessionDir.name,
            deviceName = devicePart,
            deviceAddress = null,
            startedAtMs = startedAtMs,
            endedAtMs = startedAtMs + segments.sumOf { it.durationMs },
            segments = segments.toMutableList(),
            vad = VadSummary(status = "running"),
            legacy = true,
            sourceOpus = legacyOpus.name
        )
        SessionManifestIO.write(sessionDir, manifest)
        onLog("Migrated ${segments.size} segments to ${sessionDir.name}")
        VadJobQueue.enqueueSessionSegments(sessionDir)
        return Result(sessionDir, segments.size, "OK: ${segments.size} segments, VAD queued")
    }

    private fun splitOpusIntoSegments(
        legacyOpus: File,
        sessionDir: File,
        onLog: (String) -> Unit,
        onProgress: (phase: ProgressPhase, current: Int, total: Int) -> Unit
    ): List<AudioSegmentEntry> {
        val totalBytes = legacyOpus.length()
        val totalFrames = (totalBytes / FRAME_BYTES).toInt()
        if (totalFrames == 0) throw IllegalArgumentException("Opus file empty")

        val entries = mutableListOf<AudioSegmentEntry>()
        var lastReportedPercent = -1
        FileInputStream(legacyOpus).use { input ->
            val frameBuf = ByteArray(FRAME_BYTES)
            var segIndex = 0
            var framesInSeg = 0
            var out: FileOutputStream? = null
            var segBytes = 0L
            var segStartMs = 0L

            fun openSeg() {
                val rel = SessionPaths.segmentRelativePath(segIndex)
                out = FileOutputStream(File(sessionDir, rel))
                segBytes = 0
                segStartMs = segIndex * SessionPaths.SEGMENT_DURATION_MS
                framesInSeg = 0
            }

            fun closeSeg() {
                out?.flush()
                out?.close()
                out = null
                if (segBytes > 0) {
                    val durationMs = framesInSeg * 20L
                    entries.add(
                        AudioSegmentEntry(
                            index = segIndex,
                            file = SessionPaths.segmentRelativePath(segIndex),
                            startMs = segStartMs,
                            endMs = segStartMs + durationMs,
                            bytes = segBytes,
                            durationMs = durationMs
                        )
                    )
                    onLog("Segment $segIndex: $segBytes bytes")
                    segIndex++
                }
            }

            openSeg()
            for (f in 0 until totalFrames) {
                val read = input.read(frameBuf)
                if (read < FRAME_BYTES) break
                out?.write(frameBuf)
                segBytes += FRAME_BYTES
                framesInSeg++
                val pct = (f * 100 / totalFrames.coerceAtLeast(1))
                if (pct != lastReportedPercent) {
                    lastReportedPercent = pct
                    onProgress(ProgressPhase.SPLITTING, f, totalFrames)
                }
                if (framesInSeg >= FRAMES_PER_SEGMENT) {
                    closeSeg()
                    openSeg()
                }
            }
            closeSeg()
        }
        return entries
    }
}
