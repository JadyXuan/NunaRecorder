package com.example.nunarecorder.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarControlOwnershipTest {
    @Test
    fun dutyCycleInactiveStateDoesNotClearEnableOwnership() {
        val ownership = RadarControlOwnership()

        ownership.onEnableConfirmed()
        ownership.onDeviceActiveState(false)

        assertTrue(ownership.requestedByApp)
        ownership.onDeviceActiveState(true)
        assertTrue(ownership.requestedByApp)
        ownership.onDisableConfirmed()
        assertFalse(ownership.requestedByApp)
    }
}
