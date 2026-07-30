package com.example.nunarecorder.lifelog

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LifelogApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: LifelogApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = LifelogApiClient(
            OkHttpClient(),
            server.url("/").toString(),
            "test-user"
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun fallsBackToPrototypePendingRouteOn404() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(resource("lifelog/pending.json"))
        )

        val result = client.pending()

        assertEquals(42L, result.prompts.single().eventId)
        assertEquals("/api/v1/annotations/pending", server.takeRequest().path)
        val fallback = server.takeRequest()
        assertEquals("/api/pending", fallback.path)
        assertEquals("test-user", fallback.getHeader("X-User-Id"))
    }

    @Test
    fun sendsCorrectAnnotationContract() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(resource("lifelog/annotation_response.json"))
        )

        val result = client.annotate(42, "confirm", null)

        assertEquals(9L, result.annotationId)
        assertEquals(42L, result.eventId)
        assertEquals("confirm", result.action)
        assertEquals("meeting", result.effectiveLabel)
        assertTrue(result.memoryUpdated)
        val request = server.takeRequest()
        assertEquals("/api/v1/annotations", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals(42L, body.getLong("event_id"))
        assertEquals("confirm", body.getString("action"))
        assertTrue(body.isNull("label"))
        assertTrue(request.getHeader("X-Lifelog-Client")!!.contains("NunaRecorder"))
    }

    @Test
    fun requestsDiaryForSelectedUtcDate() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(resource("lifelog/diary.json"))
        )

        val result = client.diary("2026-04-07")

        assertEquals("讨论项目进度", result.entries.single().summary)
        val request = server.takeRequest()
        assertEquals("/api/v1/diary?date=2026-04-07", request.path)
        assertEquals("test-user", request.getHeader("X-User-Id"))
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader?.getResource(path)).readText()
}
