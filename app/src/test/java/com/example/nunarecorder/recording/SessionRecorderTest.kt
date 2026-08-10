package com.example.nunarecorder.recording

import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.MmWaveSummary
import com.example.nunarecorder.session.SessionPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.nio.file.Files

class SessionRecorderTest {

    @Test
    fun audioTimelineUsesMonotonicSessionOffsetAndRetainsDeviceTimestamp() {
        val root = Files.createTempDirectory("nuna-audio-timeline-test").toFile()
        var wallClockMs = 1_000L
        var monotonicClockMs = 5_000L
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
                deviceAddress = null,
                recordingOptions = RecordingOptions(
                    segmentEnabled = true,
                    segmentDurationMs = 60_000L,
                    autoVadOnRecord = false
                )
            )
            // A wall-clock correction must not move the session timeline.
            wallClockMs = 99_000L
            monotonicClockMs = 6_500L
            recorder.feed(audioNotification(frameId = 1, timestampMs = 123_456L))
            recorder.stop()

            val row = JSONObject(SessionPaths.audioTimelineFile(sessionDir).readLines().single())
            assertEquals(1_500L, row.getLong("session_offset_ms"))
            assertEquals(2_500L, row.getLong("received_at_ms"))
            assertEquals(123_456L, row.getLong("device_timestamp_ms"))
            val manifest = SessionManifest.load(SessionPaths.manifestFile(sessionDir))!!
            assertEquals(2_500L, manifest.endedAtMs)
        } finally {
            root.deleteRecursively()
        }
    }

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
            assertEquals(130_000L, manifest.segments.single().startMs)
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
    fun productSegmentRotationWaitsForNextCompleteGroupBoundary() {
        val root = Files.createTempDirectory("nuna-product-rotation-test").toFile()
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
                deviceName = "Product Nuna",
                deviceAddress = null,
                recordingOptions = RecordingOptions(
                    segmentEnabled = true,
                    segmentDurationMs = 60_000L,
                    autoVadOnRecord = false
                )
            )
            repeat(2) { recorder.feed(productNunaNotification(frameId = 10, chunkId = it)) }
            monotonicClockMs += 60_010L
            for (chunkId in 2 until 6) {
                recorder.feed(productNunaNotification(frameId = 10, chunkId = chunkId))
            }
            repeat(6) { recorder.feed(productNunaNotification(frameId = 11, chunkId = it)) }
            recorder.stop()

            val segments = SessionManifest.load(SessionPaths.manifestFile(sessionDir))!!.segments
            assertEquals(2, segments.size)
            assertTrue(segments.all { it.integrityOk })
            assertEquals(2_880L, segments[0].bytes)
            assertEquals(2_880L, segments[1].bytes)
            assertEquals(60_010L, segments[1].startMs)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun intentionalStopTrimsIncompleteProductTailWithoutRejectingSegment() {
        val root = Files.createTempDirectory("nuna-product-stop-tail-test").toFile()
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
                deviceAddress = null,
                recordingOptions = RecordingOptions(
                    segmentEnabled = true,
                    segmentDurationMs = 60_000L,
                    autoVadOnRecord = false
                )
            )
            repeat(6) { recorder.feed(productNunaNotification(frameId = 20, chunkId = it)) }
            repeat(2) { recorder.feed(productNunaNotification(frameId = 21, chunkId = it)) }
            recorder.stop()

            val segment = SessionManifest.load(SessionPaths.manifestFile(sessionDir))!!.segments.single()
            assertTrue(segment.integrityOk)
            assertEquals(2_880L, segment.bytes)
            val events = SessionPaths.audioTimelineFile(sessionDir).readLines().map(::JSONObject)
            assertTrue(events.any {
                it.optString("type") == "audio_boundary_event" &&
                    it.optString("event") == "trimmed_incomplete_tail"
            })
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
            assertEquals(listOf(0L, 60_001L), manifest.segments.map { it.startMs })
            assertTrue(manifest.segments.all { it.bytes == 80L })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mmWavePacketsAreFlushedBeforeSessionStopReturns() {
        val root = Files.createTempDirectory("nuna-mmwave-session-test").toFile()
        val sessionDir = root.resolve("session")
        val recorder = SessionRecorder(
            onLog = {},
            wallClockMs = { 2_000L },
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
                    autoVadOnRecord = false,
                    mmWaveCaptureEnabled = true
                )
            )
            val data = byteArrayOf(
                0x01,
                0xD2.toByte(), 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x55, 0x66
            )
            val packet = byteArrayOf(
                0xAA.toByte(), 0x08, data.size.toByte(), 0x00, 0x01, 0x34, 0x12
            ) + data

            assertEquals(MmWaveCaptureResult.Queued, recorder.feedMmWave(packet))
            recorder.recordMmWaveState(
                enabled = false,
                requestedByApp = true,
                source = "a001_0x13"
            )
            recorder.stop()

            assertEquals(1, SessionPaths.mmWaveFile(sessionDir).readLines().size)
            val manifest = SessionManifest.load(SessionPaths.manifestFile(sessionDir))!!
            assertTrue("mmwave" in manifest.contextModalities)
            assertTrue(manifest.mmWave.enabled)
            assertEquals(MmWaveSummary.STATUS_CAPTURED, manifest.mmWave.status)
            assertEquals(1L, manifest.mmWave.packetCount)
            assertEquals(2L, manifest.mmWave.payloadBytes)
            assertTrue((manifest.mmWave.fileBytes ?: 0L) > 0L)
            assertEquals(1L, manifest.mmWave.stateEventCount)
            assertTrue((manifest.mmWave.stateFileBytes ?: 0L) > 0L)
            assertTrue(SessionPaths.mmWaveStateFile(sessionDir).isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun enabledMmWaveWithoutPacketsIsExplicitlyMarkedNoData() {
        val root = Files.createTempDirectory("nuna-mmwave-empty-test").toFile()
        val sessionDir = root.resolve("session")
        val logs = mutableListOf<String>()
        val recorder = SessionRecorder(
            onLog = logs::add,
            wallClockMs = { 2_000L },
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
                    autoVadOnRecord = false,
                    mmWaveCaptureEnabled = true
                )
            )
            recorder.feed(audioNotification(frameId = 1))
            recorder.stop()

            val manifest = SessionManifest.load(SessionPaths.manifestFile(sessionDir))!!
            assertEquals(MmWaveSummary.STATUS_NO_DATA, manifest.mmWave.status)
            assertEquals(0L, manifest.mmWave.packetCount)
            assertFalse(SessionPaths.mmWaveFile(sessionDir).exists())
            assertTrue(logs.any { it.contains("未收到有效数据") })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun audioNotification(frameId: Int, timestampMs: Long = 0L): ByteArray {
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
            putLe64(13, timestampMs)
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

    private fun ByteArray.putLe64(offset: Int, value: Long) {
        for (index in 0 until 8) {
            this[offset + index] = ((value ushr (index * 8)) and 0xFF).toByte()
        }
    }
}
