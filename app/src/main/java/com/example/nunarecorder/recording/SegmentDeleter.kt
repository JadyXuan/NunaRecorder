package com.example.nunarecorder.recording

import com.example.nunarecorder.session.SegmentDeletion
import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionManifestIO
import com.example.nunarecorder.session.SessionPaths
import com.example.nunarecorder.util.writeTextAtomic
import com.example.nunarecorder.vad.VadPrelabelWriter
import org.json.JSONObject
import java.io.File

/**
 * 按 1 分钟片段删除，粒度与数据粒度一致。
 *
 * 原来本地只能整会话删除，而一个会话是一整天——参与者想撤回一分钟就得扔掉全天。
 * 产品决定是以云端删除为主、本地删除为补充，但本地这条路的粒度必须先对齐。
 *
 * 三处引用必须同时清掉，不能留悬空引用：
 * 1. `audio/seg_XXX.opus` 文件本身
 * 2. `manifest.json` 的 `audio.segments[]` 条目
 * 3. `labels/vad_prelabel.json` 里对应 index 的条目
 *
 * 删除会**显式记进 `audio.deleted_segments`**。这一点很重要：分段序号本来就会因为
 * 链路中断出现空洞（`missing_segments`），如果参与者删掉的那一分钟只是悄悄消失，
 * 研究者就分不清「这一分钟没采到」和「参与者撤回了这一分钟」——
 * 一个是数据质量问题，一个是知情同意事实。
 */
object SegmentDeleter {

    data class Result(val ok: Boolean, val message: String)

    fun deleteSegment(sessionDir: File, index: Int, nowMs: Long = System.currentTimeMillis()): Result {
        val manifestFile = SessionPaths.manifestFile(sessionDir)
        val manifest = SessionManifest.load(manifestFile)
            ?: return Result(false, "读不到 manifest.json，未删除任何内容")

        val entry = manifest.segments.find { it.index == index }
            ?: return Result(false, "分段 $index 不在 manifest 里")

        val audioFile = File(sessionDir, entry.file)
        if (audioFile.exists() && !audioFile.delete()) {
            return Result(false, "删除音频文件失败：${entry.file}")
        }

        manifest.segments.removeAll { it.index == index }
        if (manifest.deletedSegments.none { it.index == index }) {
            manifest.deletedSegments = manifest.deletedSegments + SegmentDeletion(index, nowMs)
        }
        SessionManifestIO.write(sessionDir, manifest)

        removeVadEntry(sessionDir, index)

        return Result(true, "已删除第 $index 段（约第 ${index + 1} 分钟）")
    }

    /**
     * 整会话删除的前置条件：内部片段必须先删干净。
     *
     * 一次点击就丢掉一整天的采集，对参与者和研究者都太容易误操作。
     */
    /**
     * 删掉这个会话里**所有 VAD 判定有语音的段**。
     *
     * 用户 2026-08-10 明确要的兜底：「如果隐私优先、那段内容真的很敏感」，
     * 代价是少一点数据和报酬，但隐私完全包住。**调用方必须先把这个代价说清楚**——
     * 一个删掉一整天大半数据的按钮，不能只写"确定吗"。
     *
     * 判据用 `has_speech`，和服务端 ASR 路由同一个字段，所以"界面上说有语音的"
     * 和"会被删掉的"永远是同一批，不会出现两套口径。
     *
     * VAD 还没跑完的段**不删**：`status != "ok"` 意味着我们并不知道它有没有语音，
     * 拿"不知道"当"没有"会漏删，拿"不知道"当"有"会误删——两种都不能接受，
     * 所以交回调用方去等 VAD 跑完。
     */
    fun deleteSpeechSegments(
        sessionDir: File,
        nowMs: Long = System.currentTimeMillis()
    ): BulkResult {
        val entries = VadPrelabelWriter.loadSegments(SessionPaths.vadPrelabelFile(sessionDir))
        val targets = entries.filter { it.status == "ok" && it.hasSpeech }.map { it.index }
        val unknown = entries.count { it.status != "ok" }
        var deleted = 0
        val failed = mutableListOf<Int>()
        for (i in targets) {
            if (deleteSegment(sessionDir, i, nowMs).ok) deleted++ else failed.add(i)
        }
        return BulkResult(deleted, failed, unknown)
    }

    data class BulkResult(
        val deleted: Int,
        val failed: List<Int>,
        /** VAD 还没跑完、无法判断的段数；这些一个都没动 */
        val unknown: Int
    )

    /** 有语音的段数与总段数，用于把删除代价说清楚 */
    fun speechSegmentCount(sessionDir: File): Pair<Int, Int> {
        val entries = VadPrelabelWriter.loadSegments(SessionPaths.vadPrelabelFile(sessionDir))
        return entries.count { it.status == "ok" && it.hasSpeech } to entries.size
    }

    fun canDeleteSession(sessionDir: File): Boolean {
        val manifest = SessionManifest.load(SessionPaths.manifestFile(sessionDir)) ?: return true
        return manifest.segments.isEmpty()
    }

    fun remainingSegmentCount(sessionDir: File): Int =
        SessionManifest.load(SessionPaths.manifestFile(sessionDir))?.segments?.size ?: 0

    /** 同步清掉 VAD 预标注，并把 summary 重新算一遍，不留悬空条目。 */
    private fun removeVadEntry(sessionDir: File, index: Int) {
        val file = SessionPaths.vadPrelabelFile(sessionDir)
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            val segments = root.optJSONArray("segments") ?: return
            var speech = 0
            var analyzed = 0
            val kept = org.json.JSONArray()
            for (i in 0 until segments.length()) {
                val o = segments.getJSONObject(i)
                if (o.optInt("index") == index) continue
                kept.put(o)
                if (o.optBoolean("has_speech")) speech++
                if (o.optString("status") == "done") analyzed++
            }
            root.put("segments", kept)
            root.put(
                "summary",
                JSONObject().apply {
                    put("total_segments", kept.length())
                    put("speech_segments", speech)
                    put("analyzed_segments", analyzed)
                }
            )
            file.writeTextAtomic(root.toString(2))
        }
    }
}
