package com.example.nunarecorder.sync

import com.example.nunarecorder.session.AudioSegmentEntry
import com.example.nunarecorder.session.SessionManifest
import okhttp3.OkHttpClient
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SessionAutoUploadTest {

    @Test
    fun convertsRelativeSegmentOffsetToEpochAndDerivesDurationFromFrames() {
        val manifest = SessionManifest(
            sessionId = "session-123",
            deviceName = "Nuna",
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            startedAtMs = 1_775_562_510_923L
        )
        val segment = AudioSegmentEntry(
            index = 1,
            file = "audio/seg_001.opus",
            startMs = 60_000L,
            endMs = 120_000L,
            bytes = 240_000L,
            durationMs = 60_000L
        )

        val result = uploadTimeRange(manifest, segment, actualBytes = 160L)

        assertEquals(1_775_562_570_923L, result.startTimeMs)
        assertEquals(1_775_562_570_963L, result.endTimeMs)
    }

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
    fun ignoresV1ReceiptsThatUsedRelativeTimestamps() {
        val dir = Files.createTempDirectory("nuna-auto-upload-v1").toFile()
        try {
            val stateFile = dir.resolve("labels/lifelog_auto_upload.json")
            stateFile.parentFile?.mkdirs()
            stateFile.writeText(
                """{"version":1,"uploaded":{"audio/seg_000.opus":"abc123"}}"""
            )

            val state = SessionAutoUploadState(dir)

            assertFalse(state.isUploaded("audio/seg_000.opus", "abc123"))
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
                "AA:BB:CC:DD:EE:FF",
                "pilot-user",
                "pilot-pass"
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
            assertEquals(
                Credentials.basic("pilot-user", "pilot-pass"),
                request.getHeader("Authorization")
            )
            val body = request.body.readUtf8()
            assertTrue(body.contains("session-123_seg_000.opus"))
            assertTrue(body.contains("\"startTime\":123000"))
            assertTrue(body.contains("\"endTime\":183000"))
            assertFalse(body.contains("\"clientUploadId\""))
            assertTrue(body.contains("application/json"))
        } finally {
            audio.delete()
            server.shutdown()
        }
    }
}
