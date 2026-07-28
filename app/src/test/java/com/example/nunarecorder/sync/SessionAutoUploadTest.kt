package com.example.nunarecorder.sync

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SessionAutoUploadTest {

    @Test
    fun persistsUploadedSegmentHash() {
        val dir = Files.createTempDirectory("nuna-auto-upload").toFile()
        try {
            val state = SessionAutoUploadState(dir)
            state.markUploaded("audio/seg_000.opus", "abc123")

            val reloaded = SessionAutoUploadState(dir)
            assertTrue(reloaded.isUploaded("audio/seg_000.opus", "abc123"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sendsUniqueNameExactTimesAndIdempotencyKey() {
        val server = MockWebServer()
        server.start()
        val audio = Files.createTempFile("seg", ".opus").toFile().apply {
            writeBytes(ByteArray(160) { it.toByte() })
        }
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":200}"""))
            val uploader = SessionSyncUploader(
                OkHttpClient(),
                server.url("/").toString().trimEnd('/'),
                "user-1",
                "AA:BB:CC:DD:EE:FF"
            )

            val ok = uploader.uploadAudioSegment(
                file = audio,
                remoteName = "session-123_seg_000.opus",
                startTimeMs = 123_000L,
                endTimeMs = 183_000L,
                clientUploadId = "session-123:0:sha"
            )

            assertTrue(ok)
            val request = server.takeRequest()
            assertEquals("/thingx/api/file/upload/audio", request.path)
            assertEquals("session-123:0:sha", request.getHeader("Idempotency-Key"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("session-123_seg_000.opus"))
            assertTrue(body.contains("\"startTime\":123000"))
            assertTrue(body.contains("\"endTime\":183000"))
            assertTrue(body.contains("\"clientUploadId\":\"session-123:0:sha\""))
        } finally {
            audio.delete()
            server.shutdown()
        }
    }
}
