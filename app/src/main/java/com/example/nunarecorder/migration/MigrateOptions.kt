package com.example.nunarecorder.migration

data class MigrateOptions(
    val doSplit: Boolean = true,
    val doVad: Boolean = true,
    val segmentDurationSec: Int = 60
)
