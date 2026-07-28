package com.example.nunarecorder.lifelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LifelogJsonTest {

    @Test
    fun parsesTimelineFixture() {
        val result = LifelogJson.parseTimeline(resource("lifelog/timeline.json"))

        assertEquals("2026-07-28", result.date)
        assertEquals(1, result.schemaVersion)
        assertEquals(3, result.taxonomy.size)
        assertEquals(1, result.entries.size)
        val segment = result.entries.single()
        assertEquals(101L, segment.segmentId)
        assertEquals("meeting", segment.predictedLabel)
        assertEquals("rag", segment.predictedSource)
        assertNull(segment.confirmedLabel)
        assertEquals(2, segment.soundEvents.size)
        assertEquals(0.44, segment.soundEvents[1].probability, 0.0001)
    }

    @Test
    fun parsesPendingFixture() {
        val result = LifelogJson.parsePending(resource("lifelog/pending.json"))

        assertEquals(1, result.schemaVersion)
        val prompt = result.prompts.single()
        assertEquals(42L, prompt.eventId)
        assertEquals("block", prompt.kind)
        assertEquals("meeting", prompt.suggestedLabel)
        assertEquals(1785286865000L, prompt.expiresTimeMs)
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader?.getResource(path)).readText()
}
