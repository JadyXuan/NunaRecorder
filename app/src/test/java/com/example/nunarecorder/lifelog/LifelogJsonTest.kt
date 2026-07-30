package com.example.nunarecorder.lifelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LifelogJsonTest {

    @Test
    fun parsesTimelineFixture() {
        val result = LifelogJson.parseTimeline(resource("lifelog/timeline.json"))

        assertEquals("2026-04-07", result.date)
        assertEquals(1, result.schemaVersion)
        assertEquals(10, result.taxonomy.size)
        assertEquals(1, result.entries.size)
        val segment = result.entries.single()
        assertEquals(1001L, segment.segmentId)
        assertEquals("meeting", segment.predictedLabel)
        assertEquals("personal_rag", segment.predictedSource)
        assertNull(segment.confirmedLabel)
        assertNull(segment.reviewAction)
        assertEquals(1, segment.soundEvents.size)
        assertEquals("Speech", segment.soundEvents.single().name)
        assertEquals(0.91, segment.soundEvents.single().probability, 0.0001)
    }

    @Test
    fun parsesPendingFixture() {
        val result = LifelogJson.parsePending(resource("lifelog/pending.json"))

        assertEquals(1, result.schemaVersion)
        val prompt = result.prompts.single()
        assertEquals(42L, prompt.eventId)
        assertEquals("block", prompt.kind)
        assertEquals("meeting", prompt.suggestedLabel)
        assertEquals("会议/讨论", prompt.suggestedName)
        assertEquals("讨论项目进度", prompt.asrText)
        assertEquals(1775648941000L, prompt.expiresTimeMs)
    }

    @Test
    fun parsesDiaryFixture() {
        val result = LifelogJson.parseDiary(resource("lifelog/diary.json"))

        assertEquals("2026-04-07", result.date)
        assertEquals(1, result.schemaVersion)
        val entry = result.entries.single()
        assertEquals("meeting", entry.label)
        assertEquals("会议/讨论", entry.displayName)
        assertEquals("讨论项目进度", entry.summary)
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader?.getResource(path)).readText()
}
