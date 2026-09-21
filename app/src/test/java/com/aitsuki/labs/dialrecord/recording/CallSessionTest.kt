package com.aitsuki.labs.dialrecord.recording

import com.aitsuki.labs.dialrecord.recording.CallSession.Effect.*
import com.aitsuki.labs.dialrecord.recording.CallSession.PhoneState.*
import org.junit.Assert.assertEquals
import org.junit.Test

class CallSessionTest {
    private fun waitingSession() = CallSession().apply { begin() }

    @Test fun idleAllowsDialAndIgnoresPhoneEvents() {
        val session = CallSession()
        assertEquals(CallSession.State.IDLE, session.state)
        assertEquals(true, session.canStartDial)
        assertEquals(false, session.hasSession)
        assertEquals(false, session.isWaitingForOffHook)
        assertEquals(NONE, session.onPhoneState(OFFHOOK))
        assertEquals(NONE, session.onPhoneState(RINGING))
        assertEquals(NONE, session.onPhoneState(IDLE))
    }

    @Test fun dialAvailabilityFollowsTheWholeLifecycle() {
        val session = waitingSession()
        assertEquals(true, session.canStartDial)
        assertEquals(true, session.hasSession)
        assertEquals(true, session.isWaitingForOffHook)

        session.onPhoneState(OFFHOOK)
        assertEquals(CallSession.State.CALL_ACTIVE, session.state)
        assertEquals(false, session.canStartDial)
        assertEquals(true, session.hasSession)
        assertEquals(false, session.isWaitingForOffHook)

        session.onPhoneState(IDLE)
        assertEquals(CallSession.State.FINALIZING, session.state)
        assertEquals(false, session.canStartDial)
        assertEquals(true, session.hasSession)
        assertEquals(false, session.isWaitingForOffHook)

        session.reset()
        assertEquals(true, session.canStartDial)
        assertEquals(false, session.hasSession)
    }

    @Test fun waitingRequestCanBeReplacedAfterCleanup() {
        val session = waitingSession()
        assertEquals(true, session.canStartDial)
        session.beginFinalizing()
        assertEquals(false, session.canStartDial)
        session.reset()
        session.begin()
        assertEquals(true, session.isWaitingForOffHook)
        assertEquals(START_RECORDING, session.onPhoneState(OFFHOOK))
    }

    @Test(expected = IllegalStateException::class)
    fun beginCannotSkipCleanup() {
        waitingSession().begin()
    }

    @Test(expected = IllegalStateException::class)
    fun cannotBeginWhileFinalizing() {
        val session = waitingSession()
        session.onPhoneState(OFFHOOK)
        session.onPhoneState(IDLE)
        session.begin()
    }

    @Test fun initialIdleDoesNotStopWaitingSession() {
        val session = waitingSession()
        assertEquals(NONE, session.onPhoneState(IDLE))
        assertEquals(true, session.canStartDial)
        assertEquals(START_RECORDING, session.onPhoneState(OFFHOOK))
    }

    @Test fun duplicateOffhookStartsOnlyOneRecording() {
        val session = waitingSession()
        assertEquals(START_RECORDING, session.onPhoneState(OFFHOOK))
        assertEquals(NONE, session.onPhoneState(OFFHOOK))
        assertEquals(BEGIN_FINALIZATION, session.onPhoneState(IDLE))
        assertEquals(NONE, session.onPhoneState(IDLE))
    }

    @Test fun incomingCallDisarmsWaitingSession() {
        val session = waitingSession()
        assertEquals(BEGIN_FINALIZATION, session.onPhoneState(RINGING))
        assertEquals(NONE, session.onPhoneState(OFFHOOK))
        assertEquals(false, session.canStartDial)
    }

    @Test fun finalizingSessionNeverRecordsTheNextCall() {
        val session = waitingSession()
        session.onPhoneState(OFFHOOK)
        session.onPhoneState(IDLE)
        assertEquals(NONE, session.onPhoneState(OFFHOOK))
        assertEquals(NONE, session.onPhoneState(RINGING))
        assertEquals(NONE, session.onPhoneState(IDLE))
        assertEquals(CallSession.State.FINALIZING, session.state)
    }

    @Test fun cancelledOrTimedOutSessionDoesNotRecord() {
        val session = waitingSession()
        session.beginFinalizing()
        assertEquals(false, session.isWaitingForOffHook)
        assertEquals(NONE, session.onPhoneState(OFFHOOK))
        session.reset()
        assertEquals(NONE, session.onPhoneState(OFFHOOK))
        assertEquals(true, session.canStartDial)
    }

    @Test fun callWaitingDoesNotRestartOrStopRecording() {
        val session = waitingSession()
        session.onPhoneState(OFFHOOK)
        assertEquals(NONE, session.onPhoneState(RINGING))
        assertEquals(NONE, session.onPhoneState(OFFHOOK))
        assertEquals(BEGIN_FINALIZATION, session.onPhoneState(IDLE))
    }
}
