package com.example.nunarecorder.sync

import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionPaths
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SessionSyncInventoryTest {
    @Test
    fun fullContextInventoryIncludesAudioAndRadarTimelines() {
        val dir = Files.createTempDirectory("session-inventory").toFile()
        try {
            SessionPaths.manifestFile(dir).writeText("{}")
            SessionPaths.audioTimelineFile(dir).apply {
                parentFile?.mkdirs()
                writeText("{}\n")
            }
            SessionPaths.mmWaveFile(dir).apply {
                parentFile?.mkdirs()
                writeText("{}\n")
            }
            SessionPaths.mmWaveStateFile(dir).writeText("{}\n")
            val manifest = SessionManifest(
                sessionId = "session-1",
                deviceName = "Nuna",
                deviceAddress = null,
                startedAtMs = 1_000L
            )

            val paths = SessionSyncInventory.buildRelative(
                sessionDir = dir,
                manifest = manifest,
                includeContext = true,
                includeVad = false
            ).map { it.path }

            assertTrue(SessionPaths.AUDIO_TIMELINE_FILE in paths)
            assertTrue(SessionPaths.MMWAVE_FILE in paths)
            assertTrue(SessionPaths.MMWAVE_STATE_FILE in paths)
        } finally {
            dir.deleteRecursively()
        }
    }
}
