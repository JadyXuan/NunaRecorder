package com.example.nunarecorder.recording

import com.example.nunarecorder.session.AudioSegmentEntry
import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionManifestIO
import com.example.nunarecorder.session.SessionPaths
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SegmentDeleterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun session(segmentCount: Int = 3): File {
        val dir = temp.newFolder("nuna_TEST_1800000000000")
        val manifest = SessionManifest(
            sessionId = dir.name,
            deviceName = "dev",
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            startedAtMs = 1_800_000_000_000L,
            segments = (0 until segmentCount).map { i ->
                AudioSegmentEntry(
                    index = i,
                    file = SessionPaths.segmentRelativePath(i),
                    startMs = i * 60_000L,
                    endMs = i * 60_000L + 60_000L,
                    bytes = 240_000L,
                    durationMs = 60_000L
                )
            }.toMutableList()
        )
        SessionManifestIO.write(dir, manifest)
        SessionPaths.audioDir(dir).mkdirs()
        (0 until segmentCount).forEach { i ->
            File(dir, SessionPaths.segmentRelativePath(i)).writeBytes(ByteArray(240_000))
        }
        File(dir, "labels").mkdirs()
        File(dir, SessionPaths.VAD_PRELABEL_FILE).writeText(
            JSONObject().apply {
                put("format_version", 1)
                put("vad_engine", "silero")
                put("segments", org.json.JSONArray().apply {
                    (0 until segmentCount).forEach { i ->
                        put(JSONObject().apply {
                            put("index", i)
                            put("audio_file", SessionPaths.segmentRelativePath(i))
                            put("start_ms", i * 60_000L)
                            put("end_ms", i * 60_000L + 60_000L)
                            put("duration_ms", 60_000L)
                            put("has_speech", i == 1)
                            put("speech_ratio", 0.3)
                            put("speech_ms", 100)
                            put("analyzed_at_ms", 1L)
                            put("status", "done")
                        })
                    }
                })
                put("summary", JSONObject().apply {
                    put("total_segments", segmentCount)
                    put("speech_segments", 1)
                    put("analyzed_segments", segmentCount)
                })
            }.toString()
        )
        return dir
    }

    private fun manifestOf(dir: File) = SessionManifest.load(SessionPaths.manifestFile(dir))!!

    @Test
    fun `删除一分钟会清掉音频 manifest 条目和 VAD 条目`() {
        val dir = session()
        val result = SegmentDeleter.deleteSegment(dir, 1, nowMs = 1_800_000_500_000L)

        assertTrue(result.message, result.ok)
        assertFalse("音频文件应被删除", File(dir, SessionPaths.segmentRelativePath(1)).exists())

        val m = manifestOf(dir)
        assertEquals(listOf(0, 2), m.segments.map { it.index })

        val vad = JSONObject(File(dir, SessionPaths.VAD_PRELABEL_FILE).readText())
        val indices = vad.getJSONArray("segments").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getInt("index") }
        }
        assertEquals("不能留下悬空的 VAD 条目", listOf(0, 2), indices)
        assertEquals(2, vad.getJSONObject("summary").getInt("total_segments"))
        assertEquals("被删掉的正是唯一一段有语音的", 0, vad.getJSONObject("summary").getInt("speech_segments"))
    }

    /**
     * 删除必须留痕。分段序号本来就会因为链路中断出现空洞，
     * 参与者撤回的那一分钟要是也只是悄悄消失，研究者就分不清
     * 「没采到」和「被撤回」——一个是数据质量问题，一个是知情同意事实。
     */
    @Test
    fun `删除被显式记进 manifest 且与链路空洞区分开`() {
        val dir = session()
        SessionManifestIO.write(dir, manifestOf(dir).apply { missingSegments = listOf(5) })

        SegmentDeleter.deleteSegment(dir, 2, nowMs = 1_800_000_500_000L)

        val m = manifestOf(dir)
        assertEquals(listOf(2), m.deletedSegments.map { it.index })
        assertEquals(1_800_000_500_000L, m.deletedSegments.single().deletedAtMs)
        assertEquals("链路空洞不受影响", listOf(5), m.missingSegments)
    }

    @Test
    fun `重复删除同一段不会写重复记录`() {
        val dir = session()
        assertTrue(SegmentDeleter.deleteSegment(dir, 0).ok)
        assertFalse("第二次应该报告分段已不在", SegmentDeleter.deleteSegment(dir, 0).ok)
        assertEquals(1, manifestOf(dir).deletedSegments.size)
    }

    @Test
    fun `删除不存在的分段不改动任何东西`() {
        val dir = session()
        val result = SegmentDeleter.deleteSegment(dir, 99)

        assertFalse(result.ok)
        assertEquals(3, manifestOf(dir).segments.size)
        assertTrue(manifestOf(dir).deletedSegments.isEmpty())
    }

    /** 整会话删除会一次丢掉一整天，必须先把片段删干净。 */
    @Test
    fun `还有片段时不允许删除整个会话`() {
        val dir = session(2)
        assertFalse(SegmentDeleter.canDeleteSession(dir))
        assertEquals(2, SegmentDeleter.remainingSegmentCount(dir))

        SegmentDeleter.deleteSegment(dir, 0)
        assertFalse(SegmentDeleter.canDeleteSession(dir))

        SegmentDeleter.deleteSegment(dir, 1)
        assertTrue(SegmentDeleter.canDeleteSession(dir))
        assertEquals(0, SegmentDeleter.remainingSegmentCount(dir))
    }

    @Test
    fun `没有 VAD 文件时删除依然成功`() {
        val dir = session()
        File(dir, SessionPaths.VAD_PRELABEL_FILE).delete()

        assertTrue(SegmentDeleter.deleteSegment(dir, 1).ok)
        assertEquals(listOf(0, 2), manifestOf(dir).segments.map { it.index })
    }
}
