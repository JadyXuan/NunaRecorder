package com.example.nunarecorder.recording

import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SessionRecorderTest {

    @Test
    fun emptySegmentCrossingMultipleTimeWindowsDoesNotLoop() {
        val root = Files.createTempDirectory("nuna-recorder-test").toFile()
        var wallClockMs = 1_000L
        var monotonicClockMs = 10_000L
        val sessionDir = root.resolve("session")
        val recorder = SessionRecorder(
            onLog = {},
            wallClockMs = { wallClockMs },
            monotonicClockMs = { monotonicClockMs },
            sessionDirFactory = { _, _, _ -> sessionDir }
        )

        try {
            recorder.start(
                deviceName = "Nuna",
                deviceAddress = "F5:4F:41:1B:4A:83",
                recordingOptions = RecordingOptions(
                    segmentEnabled = true,
                    segmentDurationMs = 60_000L,
                    autoVadOnRecord = false
                )
            )

            // No audio was written in the first two windows. The next notification must rotate
            // once, reuse segment index 0, and then be persisted instead of spinning forever.
            monotonicClockMs += 130_000L
            assertNull(recorder.feed(audioNotification(frameId = 1)))
            recorder.stop()

            val manifest = SessionManifest.load(SessionPaths.manifestFile(sessionDir))!!
            assertEquals(1, manifest.segments.size)
            assertEquals(0, manifest.segments.single().index)
            assertEquals(120_000L, manifest.segments.single().startMs)
            assertEquals(80L, manifest.segments.single().bytes)
            assertEquals(80L, sessionDir.resolve("audio/seg_000.opus").length())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun frameGapMarksSegmentButRecordingContinues() {
        val root = Files.createTempDirectory("nuna-recorder-gap-test").toFile()
        val sessionDir = root.resolve("session")
        val recorder = SessionRecorder(
            onLog = {},
            wallClockMs = { 1_000L },
            monotonicClockMs = { 5_000L },
            sessionDirFactory = { _, _, _ -> sessionDir }
        )

        try {
            recorder.start(
                deviceName = "Nuna",
                deviceAddress = "4C:FF:01:A0:01:0C",
                recordingOptions = RecordingOptions(
                    segmentEnabled = true,
                    segmentDurationMs = 60_000L,
                    autoVadOnRecord = false
                )
            )

            assertNull(recorder.feed(audioNotification(frameId = 10)))
            assertTrue(recorder.feed(audioNotification(frameId = 12))?.contains("期望 11") == true)
            assertNull(recorder.feed(audioNotification(frameId = 13)))
            assertTrue(recorder.isRecording)
            recorder.stop()

            val segment = SessionManifest.load(SessionPaths.manifestFile(sessionDir))!!
                .segments.single()
            assertFalse(segment.integrityOk)
            assertTrue(segment.integrityIssue?.contains("期望 11") == true)
            assertEquals(240L, segment.bytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun productNunaBatchProducesCompleteUploadEligibleSegment() {
        val root = Files.createTempDirectory("nuna-product-session-test").toFile()
        val sessionDir = root.resolve("session")
        val recorder = SessionRecorder(
            onLog = {},
            wallClockMs = { 1_000L },
            monotonicClockMs = { 5_000L },
            sessionDirFactory = { _, _, _ -> sessionDir }
        )

        try {
            recorder.start(
                deviceName = "Product Nuna",
                deviceAddress = "4C:FF:01:A0:0D:F9",
                recordingOptions = RecordingOptions(
                    segmentEnabled = true,
                    segmentDurationMs = 60_000L,
                    autoVadOnRecord = false
                )
            )

            repeat(6) { chunkId ->
                assertNull(recorder.feed(productNunaNotification(frameId = 51, chunkId = chunkId)))
            }
            recorder.stop()

            val segment = SessionManifest.load(SessionPaths.manifestFile(sessionDir))!!
                .segments.single()
            assertTrue(segment.integrityOk)
            assertNull(segment.integrityIssue)
            assertEquals(2_880L, segment.bytes)
            assertEquals(720L, segment.durationMs)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun normalRotationKeepsSavedSegmentIndexesContiguous() {
        val root = Files.createTempDirectory("nuna-recorder-test").toFile()
        var monotonicClockMs = 5_000L
        val sessionDir = root.resolve("session")
        val recorder = SessionRecorder(
            onLog = {},
            wallClockMs = { 1_000L },
            monotonicClockMs = { monotonicClockMs },
            sessionDirFactory = { _, _, _ -> sessionDir }
        )

        try {
            recorder.start(
                deviceName = "Nuna",
                deviceAddress = null,
                recordingOptions = RecordingOptions(
                    segmentEnabled = true,
                    segmentDurationMs = 60_000L,
                    autoVadOnRecord = false
                )
            )
            recorder.feed(audioNotification(frameId = 1))
            monotonicClockMs += 60_001L
            recorder.feed(audioNotification(frameId = 2))
            recorder.stop()

            val manifest = SessionManifest.load(SessionPaths.manifestFile(sessionDir))!!
            assertEquals(listOf(0, 1), manifest.segments.map { it.index })
            assertEquals(listOf(0L, 60_000L), manifest.segments.map { it.startMs })
            assertTrue(manifest.segments.all { it.bytes == 80L })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun audioNotification(frameId: Int): ByteArray {
        val payloadLength = 14 + 80
        return ByteArray(7 + payloadLength).apply {
            this[0] = 0xAA.toByte()
            this[1] = 0x10
            putLe16(2, payloadLength)
            this[4] = 0x01
            this[5] = 0x34
            this[6] = 0x12
            putLe16(7, frameId)
            putLe16(9, 80)
            this[11] = 0
            this[12] = 1
            for (index in 0 until 80) {
                this[21 + index] = index.toByte()
            }
        }
    }

    private fun productNunaNotification(frameId: Int, chunkId: Int): ByteArray {
        val opusBytes = ByteArray(480) { (chunkId + it).toByte() }
        val payloadLength = 14 + opusBytes.size
        return ByteArray(7 + payloadLength).apply {
            this[0] = 0xAA.toByte()
            this[1] = 0x10
            putLe16(2, payloadLength)
            this[4] = 0x01
            this[5] = 0x34
            this[6] = 0x12
            putLe16(7, frameId)
            putLe16(9, 80)
            this[11] = chunkId.toByte()
            this[12] = 6
            opusBytes.copyInto(this, destinationOffset = 21)
        }
    }

    private fun ByteArray.putLe16(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
