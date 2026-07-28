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
        assertEquals("test-user", fallback.getHeader("X-User-ID"))
    }

    @Test
    fun sendsCorrectAnnotationContract() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"answered","label":"work","memory_size":7}""")
        )

        val result = client.annotate(42, "correct", "work")

        assertEquals("work", result.label)
        assertEquals(7, result.memorySize)
        val request = server.takeRequest()
        assertEquals("/api/v1/annotations", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals(42L, body.getLong("event_id"))
        assertEquals("correct", body.getString("action"))
        assertEquals("work", body.getString("label"))
        assertTrue(request.getHeader("X-Lifelog-Client")!!.contains("NunaRecorder"))
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader?.getResource(path)).readText()
}
