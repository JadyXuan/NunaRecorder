package com.example.nunarecorder.data

enum class LogLevel {
    INFO,
    DEBUG;

    fun allows(level: LogLevel): Boolean = when (this) {
        INFO -> level == INFO
        DEBUG -> true
    }
}
