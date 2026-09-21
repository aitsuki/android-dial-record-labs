package com.aitsuki.labs.dialrecord.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingStoreTest {
    @Test fun unassociatedRecordingRoundTrips() {
        val entry = RecordingEntry("15816412111", 2000)
        val json = RecordingStore.encode(entry)
        assertFalse(json.contains("callLog"))
        assertEquals(entry, RecordingStore.decode(json))
    }

    @Test fun associatedRecordingRoundTrips() {
        val entry = RecordingEntry("15816412111", 2000, CallLogInfo(42, 56))
        assertEquals(entry, RecordingStore.decode(RecordingStore.encode(entry)))
    }

    @Test fun zeroDurationIsNotLostInStorage() {
        val entry = RecordingEntry("15816412111", 2000, CallLogInfo(42, 0))
        assertEquals(entry, RecordingStore.decode(RecordingStore.encode(entry)))
    }
}
