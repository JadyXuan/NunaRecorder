package com.example.nunarecorder.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStartPolicyTest {

    private val target = RecordingStartPolicy.Target("Nuna", "AA:BB:CC:DD:EE:FF")

    @Test
    fun user_start_is_allowed_when_no_recording_intent_exists_yet() {
        val decision = RecordingStartPolicy.decide(
            RecordingStartPolicy.Origin.USER,
            requested = target,
            persistedIntent = null
        )

        assertTrue(decision is RecordingStartPolicy.Decision.Start)
        decision as RecordingStartPolicy.Decision.Start
        assertEquals(target, decision.target)
        assertTrue(decision.persistIntent)
    }

    @Test
    fun queued_system_replay_after_user_stop_cannot_trust_stale_device_extras() {
        val decision = RecordingStartPolicy.decide(
            RecordingStartPolicy.Origin.SYSTEM_RECOVERY,
            requested = target,
            persistedIntent = null
        )

        assertSame(RecordingStartPolicy.Decision.Ignore, decision)
    }

    @Test
    fun system_kill_recovers_when_user_intent_still_exists_without_rewriting_it() {
        val decision = RecordingStartPolicy.decide(
            RecordingStartPolicy.Origin.SYSTEM_RECOVERY,
            requested = null,
            persistedIntent = target
        )

        assertTrue(decision is RecordingStartPolicy.Decision.Start)
        decision as RecordingStartPolicy.Decision.Start
        assertEquals(target, decision.target)
        assertFalse(decision.persistIntent)
    }

    @Test
    fun task_removed_alarm_is_never_indistinguishable_from_user_start() {
        assertNotEquals(
            RecordingRecoveryProtocol.ACTION_USER_START,
            RecordingRecoveryProtocol.taskRemovedAlarm.action
        )
        assertEquals(
            RecordingRecoveryProtocol.ACTION_SYSTEM_RECOVER,
            RecordingRecoveryProtocol.taskRemovedAlarm.action
        )
    }

    @Test
    fun user_stop_cancels_current_watchdog_recovery_and_legacy_restart_identity() {
        assertEquals(
            setOf(
                RecordingRecoveryProtocol.AlarmSpec(
                    1, RecordingRecoveryProtocol.ACTION_SYSTEM_RECOVER
                ),
                RecordingRecoveryProtocol.AlarmSpec(
                    1, RecordingRecoveryProtocol.ACTION_USER_START
                ),
                RecordingRecoveryProtocol.AlarmSpec(
                    2, RecordingRecoveryProtocol.ACTION_WATCHDOG
                )
            ),
            RecordingRecoveryProtocol.cancellableAlarms.toSet()
        )
    }
}
