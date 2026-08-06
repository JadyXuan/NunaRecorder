package com.example.nunarecorder.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioStallMonitorTest {

    @Test
    fun reportsWarningStallAndRecoveryOnlyOnTransitions() {
        val monitor = AudioStallMonitor(warningAfterMs = 1_000L, stalledAfterMs = 3_000L)
        monitor.start(10_000L)

        assertNull(monitor.check(10_999L))
        assertEquals(AudioStallEventType.WARNING, monitor.check(11_000L)?.type)
        assertNull(monitor.check(12_000L))
        assertEquals(AudioStallEventType.STALLED, monitor.check(13_000L)?.type)
        assertNull(monitor.check(14_000L))

        val recovered = monitor.onPacket(15_000L)
        assertEquals(AudioStallEventType.RECOVERED, recovered?.type)
        assertEquals(5_000L, recovered?.silenceMs)
        assertNull(monitor.onPacket(15_020L))
    }

    @Test
    fun stopSuppressesFurtherEvents() {
        val monitor = AudioStallMonitor()
        monitor.start(0L)
        monitor.stop()

        assertNull(monitor.check(10_000L))
        assertNull(monitor.onPacket(10_020L))
    }
}
