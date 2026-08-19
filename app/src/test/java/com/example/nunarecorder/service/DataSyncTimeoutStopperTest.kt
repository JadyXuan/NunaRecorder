package com.example.nunarecorder.service

import org.junit.Assert.assertEquals
import org.junit.Test

class DataSyncTimeoutStopperTest {

    @Test
    fun cleanup_runs_before_foreground_and_service_stop() {
        val calls = mutableListOf<String>()

        DataSyncTimeoutStopper(
            cleanup = { calls += "cleanup" },
            removeForeground = { calls += "foreground" },
            stopService = { calls += "service" }
        ).stop()

        assertEquals(listOf("cleanup", "foreground", "service"), calls)
    }

    @Test
    fun cleanup_failure_cannot_skip_the_mandatory_service_stop() {
        val calls = mutableListOf<String>()

        DataSyncTimeoutStopper(
            cleanup = {
                calls += "cleanup"
                error("stale WakeLock")
            },
            removeForeground = { calls += "foreground" },
            stopService = { calls += "service" }
        ).stop()

        assertEquals(listOf("cleanup", "foreground", "service"), calls)
    }

    @Test
    fun foreground_removal_failure_cannot_skip_the_mandatory_service_stop() {
        val calls = mutableListOf<String>()

        DataSyncTimeoutStopper(
            cleanup = { calls += "cleanup" },
            removeForeground = {
                calls += "foreground"
                error("notification already removed")
            },
            stopService = { calls += "service" }
        ).stop()

        assertEquals(listOf("cleanup", "foreground", "service"), calls)
    }
}
