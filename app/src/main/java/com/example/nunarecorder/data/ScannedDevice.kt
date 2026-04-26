package com.example.nunarecorder.data

/**
 * 扫描到的 BLE 设备项（名称 + 地址）
 */
data class ScannedDevice(
    val name: String?,
    val address: String
)
