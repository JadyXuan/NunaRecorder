package com.example.nunarecorder.data

data class UserSettings(
    val userId: String = "",
    val mac: String = "",
    val serverHost: String = "10.0.2.2",
    val serverPort: Int = 9000
)

