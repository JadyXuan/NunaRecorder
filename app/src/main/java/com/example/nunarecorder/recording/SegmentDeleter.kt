package com.example.nunarecorder.recording

import com.example.nunarecorder.session.SegmentDeletion
import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionManifestIO
import com.example.nunarecorder.session.SessionPaths
import com.example.nunarecorder.util.writeTextAtomic
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
