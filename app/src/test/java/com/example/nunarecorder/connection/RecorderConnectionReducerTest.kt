package com.example.nunarecorder.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderConnectionReducerTest {

    @Test
    fun handshakeCannotStartBeforeNotificationChannelIsReady() {
        var state = RecorderConnectionState()
        state = next(state, RecorderConnectionEvent.ConnectRequested("Nuna"))
        state = next(state, RecorderConnectionEvent.GattConnected("Nuna"))
        state = next(state, RecorderConnectionEvent.ServicesDiscovered)

        val rejected = RecorderConnectionReducer.reduce(
            state,
            RecorderConnectionEvent.HandshakeStarted
        )

        assertFalse(rejected.accepted)
        assertEquals(RecorderConnectionPhase.PREPARING_HANDSHAKE, rejected.state.phase)
    }

    @Test
    fun recordingCannotStartBeforeHandshakeSucceeds() {
        var state = RecorderConnectionState()
        state = next(state, RecorderConnectionEvent.ConnectRequested("Nuna"))
        state = next(state, RecorderConnectionEvent.GattConnected("Nuna"))
        state = next(state, RecorderConnectionEvent.ServicesDiscovered)
        state = next(state, RecorderConnectionEvent.HandshakeNotificationsReady)
        state = next(state, RecorderConnectionEvent.HandshakeStarted)

        val rejected = RecorderConnectionReducer.reduce(
            state,
            RecorderConnectionEvent.RecordingStartRequested
        )

        assertFalse(rejected.accepted)
        assertEquals(RecorderConnectionPhase.HANDSHAKING, rejected.state.phase)
        assertFalse(rejected.state.canStartRecording)
    }

    @Test
    fun successfulHandshakeEnablesRecordingAndStartFailureReturnsToReady() {
        var state = readyState()
        assertTrue(state.canStartRecording)

        state = next(state, RecorderConnectionEvent.RecordingStartRequested)
        assertEquals(RecorderConnectionPhase.STARTING_RECORDING, state.phase)
        assertFalse(state.canStartRecording)

        state = next(state, RecorderConnectionEvent.RecordingStartFailed("CCCD status=5"))
        assertEquals(RecorderConnectionPhase.READY, state.phase)
        assertTrue(state.canStartRecording)
        assertFalse(state.isRecording)
    }

    @Test
    fun recordingStallAndRecoveryKeepButtonStateConsistent() {
        var state = readyState()
        state = next(state, RecorderConnectionEvent.RecordingStartRequested)
        state = next(state, RecorderConnectionEvent.RecordingTransportReady)
        assertEquals(RecorderConnectionPhase.STARTING_RECORDING, state.phase)
        assertFalse(state.isRecording)
        assertTrue(state.canStopRecording)

        state = next(state, RecorderConnectionEvent.RecordingStarted)
        assertTrue(state.isRecording)
        assertTrue(state.canStopRecording)

        state = next(state, RecorderConnectionEvent.AudioStalled)
        assertEquals(RecorderConnectionPhase.AUDIO_STALLED, state.phase)
        assertTrue(state.canStopRecording)
        assertFalse(state.canStartRecording)

        state = next(state, RecorderConnectionEvent.AudioRecovered)
        assertEquals(RecorderConnectionPhase.RECORDING, state.phase)
    }

    private fun readyState(): RecorderConnectionState {
        var state = RecorderConnectionState()
        state = next(state, RecorderConnectionEvent.ConnectRequested("Nuna"))
        state = next(state, RecorderConnectionEvent.GattConnected("Nuna"))
        state = next(state, RecorderConnectionEvent.ServicesDiscovered)
        state = next(state, RecorderConnectionEvent.HandshakeNotificationsReady)
        state = next(state, RecorderConnectionEvent.HandshakeStarted)
        return next(state, RecorderConnectionEvent.HandshakeSucceeded)
    }

    private fun next(
        current: RecorderConnectionState,
        event: RecorderConnectionEvent
    ): RecorderConnectionState {
        val transition = RecorderConnectionReducer.reduce(current, event)
        assertTrue("Expected $event from ${current.phase} to be accepted", transition.accepted)
        return transition.state
    }
}
