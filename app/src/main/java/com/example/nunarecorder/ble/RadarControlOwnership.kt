package com.example.nunarecorder.ble

/**
 * Tracks whether this App owns a logical radar ENABLE request.
 *
 * Product firmware duty-cycles the radar and reports A001 0x13=false while sleeping.
 * Those transient hardware states must not cancel the App's obligation to send DISABLE.
 */
class RadarControlOwnership {
    var requestedByApp: Boolean = false
        private set

    fun onEnableConfirmed() {
        requestedByApp = true
    }

    fun onDisableConfirmed() {
        requestedByApp = false
    }

    fun onDeviceActiveState(@Suppress("UNUSED_PARAMETER") enabled: Boolean) {
        // Deliberately independent: false may mean a power-saving sleep window.
    }

    fun reset() {
        requestedByApp = false
    }
}
