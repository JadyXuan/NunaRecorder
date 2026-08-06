package com.example.nunarecorder.diagnostics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executor

class DiagnosticLoggerTest {

    @Test
    fun writesStructuredJsonLine() {
        val root = Files.createTempDirectory("nuna-diagnostics").toFile()
        val directExecutor = Executor { command -> command.run() }
        val logger = DiagnosticLogger(
            rootDir = root,
            wallClockMs = { 1_785_843_000_000L },
            elapsedClockMs = { 123_456L },
            executor = directExecutor
        )

        try {
            logger.log(
                "gatt_state_change",
                mapOf("status" to 8, "recording" to true, "last_audio_age_ms" to 3_000L)
            )

            val files = logger.listFiles()
            assertEquals(1, files.size)
            val json = JSONObject(files.single().readLines().single())
            assertEquals("gatt_state_change", json.getString("event"))
            assertEquals(8, json.getInt("status"))
            assertEquals(true, json.getBoolean("recording"))
            assertEquals(3_000L, json.getLong("last_audio_age_ms"))
            assertFalse(json.has("basic_auth_password"))
        } finally {
            root.deleteRecursively()
        }
    }
}
