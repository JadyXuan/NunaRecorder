package com.example.nunarecorder.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryTelemetryTest {

    @Test
    fun parsesStandardBatteryLevel() {
        val state = BatteryTelemetry.parseStandardBatteryLevel(byteArrayOf(73))

        assertEquals(73, state?.percent)
        assertEquals(DevicePowerSource.STANDARD_BATTERY_SERVICE, state?.source)
        assertNull(BatteryTelemetry.parseStandardBatteryLevel(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun parsesExtendedNunaPowerTelemetry() {
        val state = BatteryTelemetry.parseNunaPower(
            byteArrayOf(1, 0b111, 0x6E, 0x0F, 82, 0)
        )

        assertEquals(3950, state?.voltageMv)
        assertEquals(82, state?.percent)
        assertTrue(state?.usbPresent == true)
        assertTrue(state?.charging == true)
        assertEquals(DevicePowerSource.NUNA_POWER, state?.source)
    }

    @Test
    fun keepsUsbStateWhenBatteryMeasurementIsInvalid() {
        val state = BatteryTelemetry.parseNunaPower(
            byteArrayOf(1, 0b001, 0, 0, 0xFF.toByte(), 0)
        )

        assertNull(state?.percent)
        assertNull(state?.voltageMv)
        assertTrue(state?.usbPresent == true)
        assertFalse(state?.charging == true)
    }

    @Test
    fun rejectsUnknownExtendedFormat() {
        assertNull(BatteryTelemetry.parseNunaPower(byteArrayOf(2, 0, 0, 0, 50, 0)))
        assertNull(BatteryTelemetry.parseNunaPower(byteArrayOf(1, 0)))
    }
}
