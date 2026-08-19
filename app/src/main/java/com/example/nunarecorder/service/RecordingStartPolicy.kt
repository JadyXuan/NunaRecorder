package com.example.nunarecorder.service

internal object RecordingRecoveryProtocol {
    const val ACTION_USER_START = "com.example.nunarecorder.action.START_RECORDING"
    const val ACTION_SYSTEM_RECOVER = "com.example.nunarecorder.action.RECOVER_RECORDING"
    const val ACTION_WATCHDOG = "com.example.nunarecorder.action.WATCHDOG_RESTART"

    data class AlarmSpec(val requestCode: Int, val action: String)

    val taskRemovedAlarm = AlarmSpec(1, ACTION_SYSTEM_RECOVER)
    val watchdogAlarm = AlarmSpec(2, ACTION_WATCHDOG)

    /**
     * ACTION_USER_START is retained here only to cancel a pre-fix alarm that may
     * survive an in-place app upgrade. New recovery alarms never use that action.
     */
    val cancellableAlarms = listOf(
        taskRemovedAlarm,
        AlarmSpec(1, ACTION_USER_START),
        watchdogAlarm
    )
}

internal object RecordingStartPolicy {

    enum class Origin { USER, SYSTEM_RECOVERY }

    data class Target(val deviceName: String, val deviceAddress: String)

    sealed class Decision {
        data class Start(val target: Target, val persistIntent: Boolean) : Decision()
        object Ignore : Decision()
    }

    fun decide(
        origin: Origin,
        requested: Target?,
        persistedIntent: Target?
    ): Decision {
        val target = when (origin) {
            Origin.USER -> requested
            Origin.SYSTEM_RECOVERY -> persistedIntent
        }?.takeIf { it.deviceAddress.isNotBlank() } ?: return Decision.Ignore

        return Decision.Start(
            target = target,
            persistIntent = origin == Origin.USER
        )
    }
}
