package com.aitsuki.labs.dialrecord.data

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RecordingEntryTest {
    private fun recording() = RecordingEntry(UUID.randomUUID().toString(),
        CallSource.SDK, "+8613812345678", 2000)

    @Test fun durationDoesNotRequireSystemCallLog() {
        assertEquals("+8613812345678_2000.m4a", recording().fileName)
        assertEquals("+8613812345678_2000_56.m4a", recording().copy(durationSeconds = 56).fileName)
        assertEquals("+8613812345678_2000_0.m4a", recording().copy(durationSeconds = 0).fileName)
    }

    @Test fun allSourcesRequirePhoneNumbers() {
        for (source in CallSource.entries) {
            for (invalid in listOf("", "sdk:user", "../user/a\\b:rtc", "+", "1".repeat(33))) {
                assertTrue("$source must reject $invalid", runCatching {
                    recording().copy(source = source, phoneNumber = invalid)
                }.exceptionOrNull() is IllegalArgumentException)
            }
            assertEquals("13800138000_2000.m4a",
                recording().copy(source = source, phoneNumber = "13800138000").fileName)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeDurationIsRejected() { recording().copy(durationSeconds = -1) }

    @Test(expected = IllegalArgumentException::class)
    fun pathCannotBeUsedAsRecordingId() { recording().copy(recordingId = "../other") }
}
