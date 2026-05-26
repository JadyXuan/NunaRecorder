package com.example.nunarecorder.vad

import android.content.Context
import android.util.Log
import com.example.nunarecorder.audio.OpusToPcmMono
import com.example.nunarecorder.service.BackgroundProcessing
import com.example.nunarecorder.service.VadProcessingService
import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

data class VadJob(
    val sessionDir: File,
    val segmentIndex: Int,
    val opusFile: File,
    val audioRelPath: String,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val sessionStartedAtMs: Long
)

/**
 * 串行 VAD 队列：段封口后（实时录制）或迁移切分后（后处理）入队。
 * 配合 [VadProcessingService] + [ProcessingWakeLock] 在锁屏后继续分析。
 */
object VadJobQueue {

    private const val TAG = "VadJobQueue"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val channel = Channel<VadJob>(Channel.UNLIMITED)
    private var appContext: Context? = null

    @Volatile
    private var consumerStarted = false

    fun start(context: Context) {
        appContext = context.applicationContext
        ensureConsumer()
    }

    private fun ensureConsumer() {
        if (consumerStarted) return
        consumerStarted = true
        Thread({
            Log.d(TAG, "VAD consumer thread started")
            runBlocking {
                for (job in channel) {
                    process(job)
                }
            }
        }, "NunaVadConsumer").apply {
            isDaemon = false
            start()
        }
    }

    fun enqueue(job: VadJob) {
        val ctx = appContext ?: return
        ensureConsumer()
        BackgroundProcessing.onJobScheduled(ctx)
        scope.launch {
            try {
                channel.send(job)
            } catch (e: Exception) {
                Log.e(TAG, "enqueue failed", e)
                BackgroundProcessing.onJobFinished(ctx)
            }
        }
    }

    /** 仅分析尚未 status=ok 的段（断点续传安全） */
    fun enqueuePendingSegments(sessionDir: File): Int {
        val manifest = SessionManifest.load(SessionPaths.manifestFile(sessionDir)) ?: return 0
        val pending = VadResumeHelper.findPendingSegments(sessionDir)
        if (pending.isEmpty()) return 0
        VadPrelabelWriter.markRunning(sessionDir, manifest.segments.size, manifest.startedAtMs)
        pending.forEach { seg ->
            enqueue(
                VadJob(
                    sessionDir = sessionDir,
                    segmentIndex = seg.index,
                    opusFile = File(sessionDir, seg.file),
                    audioRelPath = seg.file,
                    startMs = seg.startMs,
                    endMs = seg.endMs,
                    durationMs = seg.durationMs,
                    sessionStartedAtMs = manifest.startedAtMs
                )
            )
        }
        return pending.size
    }

    fun enqueueSessionSegments(sessionDir: File) {
        enqueuePendingSegments(sessionDir)
    }

    /** App 启动时自动续传所有未完成的会话 VAD */
    fun resumeAllIncompleteSessions(): Int {
        val ctx = appContext ?: return 0
        var total = 0
        VadResumeHelper.listSessionsNeedingVad().forEach { dir ->
            val n = enqueuePendingSegments(dir)
            if (n > 0) {
                Log.d(TAG, "Resume VAD: ${dir.name} pending=$n")
                total += n
            }
        }
        return total
    }

    private fun process(job: VadJob) {
        val ctx = appContext
        if (ctx == null) {
            Log.e(TAG, "VadJobQueue not started")
            return
        }
        try {
            Log.d(TAG, "VAD start seg=${job.segmentIndex} file=${job.opusFile.name}")
            val manifest = SessionManifest.load(SessionPaths.manifestFile(job.sessionDir))
            val total = manifest?.segments?.size ?: 0
            val progressText = if (total > 0) {
                "分析段 ${job.segmentIndex + 1} / $total"
            } else {
                "分析段 ${job.segmentIndex}…"
            }
            val progressPct = if (total > 0) {
                ((job.segmentIndex + 1) * 100 / total).coerceIn(0, 100)
            } else null
            VadProcessingService.updateProgress(ctx, progressText, progressPct)
            val result = try {
                val pcm = OpusToPcmMono.decodeFileToMonoFloat(job.opusFile)
                val analyzer = SileroVadAnalyzer.getInstance(ctx)
                analyzer.analyzeMonoPcm16k(pcm, job.durationMs)
            } catch (e: Exception) {
                Log.e(TAG, "VAD failed seg=${job.segmentIndex}", e)
                VadSegmentResult(
                    hasSpeech = false,
                    speechRatio = 0f,
                    speechMs = 0,
                    durationMs = job.durationMs,
                    status = "failed",
                    error = e.message
                )
            }
            VadPrelabelWriter.mergeSegment(
                sessionDir = job.sessionDir,
                segment = VadPrelabelSegment(
                    index = job.segmentIndex,
                    audioFile = job.audioRelPath,
                    startMs = job.startMs,
                    endMs = job.endMs,
                    durationMs = job.durationMs,
                    hasSpeech = result.hasSpeech,
                    speechRatio = result.speechRatio,
                    speechMs = result.speechMs,
                    analyzedAtMs = System.currentTimeMillis(),
                    status = result.status,
                    error = result.error
                ),
                sessionStartedAtMs = job.sessionStartedAtMs
            )
            Log.d(
                TAG,
                "VAD done seg=${job.segmentIndex} hasSpeech=${result.hasSpeech} ratio=${result.speechRatio}"
            )
        } finally {
            BackgroundProcessing.onJobFinished(ctx)
        }
    }
}
