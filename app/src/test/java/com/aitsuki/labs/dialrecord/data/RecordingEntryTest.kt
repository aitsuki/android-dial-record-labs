package com.aitsuki.labs.dialrecord.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingEntryTest {
    private fun recording() = RecordingEntry(
        phoneNumber = "15816412111",
        recordingStartedAtMs = 1789963250200,
    )

    @Test fun unassociatedFileUsesRecordingStartTime() {
        val entry = recording()
        assertNull(entry.callLog)
        assertEquals("15816412111_1789963250200.m4a", entry.fileName)
    }

    @Test fun associatedFileUsesCallLogDurationInSeconds() {
        val entry = recording().copy(callLog = CallLogInfo(42, 56))
        assertEquals("15816412111_1789963250200_56.m4a", entry.fileName)
    }

    @Test fun zeroDurationIsAValidCallLogResult() {
        val entry = recording().copy(callLog = CallLogInfo(42, 0))
        assertEquals("15816412111_1789963250200_0.m4a", entry.fileName)
    }

    @Test fun internationalNumberIsPreserved() {
        val entry = recording().copy(phoneNumber = "+8615816412111")
        assertEquals("+8615816412111_1789963250200.m4a", entry.fileName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeDurationIsRejected() {
        CallLogInfo(42, -1)
    }
}
