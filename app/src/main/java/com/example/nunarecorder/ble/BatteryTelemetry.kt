package com.example.nunarecorder.ble

import java.util.UUID

enum class DevicePowerAvailability {
    READING,
    AVAILABLE,
    UNSUPPORTED
}

enum class DevicePowerSource {
    NUNA_POWER,
    STANDARD_BATTERY_SERVICE
}

data class DevicePowerState(
    val availability: DevicePowerAvailability,
    val percent: Int? = null,
    val voltageMv: Int? = null,
    val usbPresent: Boolean? = null,
    val charging: Boolean? = null,
    val source: DevicePowerSource? = null
) {
    companion object {
        fun reading() = DevicePowerState(DevicePowerAvailability.READING)
        fun unsupported() = DevicePowerState(DevicePowerAvailability.UNSUPPORTED)
    }
}

/** Parsers for optional battery capabilities; unsupported Nuna devices remain usable. */
object BatteryTelemetry {
    val STANDARD_SERVICE_UUID: UUID = uuid16(0x180F)
    val STANDARD_LEVEL_UUID: UUID = uuid16(0x2A19)
    val NUNA_POWER_UUID: UUID = uuid16(0xA004)

    private const val NUNA_POWER_VERSION = 1
    private const val FLAG_USB_PRESENT = 1 shl 0
    private const val FLAG_CHARGING = 1 shl 1
    private const val FLAG_MEASUREMENT_VALID = 1 shl 2

    fun parse(characteristicUuid: UUID, value: ByteArray): DevicePowerState? = when (characteristicUuid) {
        NUNA_POWER_UUID -> parseNunaPower(value)
        STANDARD_LEVEL_UUID -> parseStandardBatteryLevel(value)
        else -> null
    }

    fun parseStandardBatteryLevel(value: ByteArray): DevicePowerState? {
        if (value.isEmpty()) return null
        val percent = value[0].toInt() and 0xFF
        if (percent !in 0..100) return null
        return DevicePowerState(
            availability = DevicePowerAvailability.AVAILABLE,
            percent = percent,
            source = DevicePowerSource.STANDARD_BATTERY_SERVICE
        )
    }

    fun parseNunaPower(value: ByteArray): DevicePowerState? {
        if (value.size < 6 || (value[0].toInt() and 0xFF) != NUNA_POWER_VERSION) return null
        val flags = value[1].toInt() and 0xFF
        val measurementValid = flags and FLAG_MEASUREMENT_VALID != 0
        val voltageMv = (value[2].toInt() and 0xFF) or
            ((value[3].toInt() and 0xFF) shl 8)
        val percent = value[4].toInt() and 0xFF
        if (measurementValid && percent !in 0..100) return null

        return DevicePowerState(
            availability = DevicePowerAvailability.AVAILABLE,
            percent = percent.takeIf { measurementValid },
            voltageMv = voltageMv.takeIf { measurementValid },
            usbPresent = flags and FLAG_USB_PRESENT != 0,
            charging = flags and FLAG_CHARGING != 0,
            source = DevicePowerSource.NUNA_POWER
        )
    }

    private fun uuid16(value: Int): UUID = UUID.fromString(
        "0000%04X-0000-1000-8000-00805F9B34FB".format(value)
    )
}
