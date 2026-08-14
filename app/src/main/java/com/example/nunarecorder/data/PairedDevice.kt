package com.example.nunarecorder.data

data class PairedDevice(
    val name: String?,
    val address: String,
    val lastConnectedTime: Long,
    /**
     * 上次采集时这台设备报上来的**权威**固件版本（A001 `0x05`），没采过则为 null。
     *
     * 存它是因为开采之前读不到真固件——DIS 对每台设备都返回 `1.0.0`。
     * 见 [DeviceFirmwarePolicy]。
     */
    val lastFirmware: String? = null
)
