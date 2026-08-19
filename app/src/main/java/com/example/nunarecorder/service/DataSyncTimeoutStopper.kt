package com.example.nunarecorder.service

/**
 * Android 15 gives a timed-out dataSync service only a few seconds to stop.
 * Resource cleanup is best-effort: neither a stale WakeLock nor an already
 * removed notification may prevent the mandatory stopSelf call.
 */
internal class DataSyncTimeoutStopper(
    private val cleanup: () -> Unit,
    private val removeForeground: () -> Unit,
    private val stopService: () -> Unit
) {
    fun stop() {
        runCatching { cleanup() }
        runCatching { removeForeground() }
        stopService()
    }
}
