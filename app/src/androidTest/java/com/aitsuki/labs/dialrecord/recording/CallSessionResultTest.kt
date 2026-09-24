package com.aitsuki.labs.dialrecord.recording

import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aitsuki.labs.dialrecord.data.CallLogInfo
import com.aitsuki.labs.dialrecord.data.RecordingEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallSessionResultTest {
    @Test fun completedResultPreservesFinalFileAndZeroDurationAcrossParcel() {
        val entry = RecordingEntry("+8613812345678", 2000, CallLogInfo(42, 0))
        val result = CallSessionResult("request-1", entry.phoneNumber,
            CallSessionResult.Outcome.COMPLETED, entry)
        val decoded = roundTrip(result)
        assertEquals(result, decoded)
        assertEquals(entry.fileName, decoded.recording?.fileName)
    }

    @Test fun unmatchedRecordingIsStillACompletedResult() {
        val entry = RecordingEntry("13812345678", 2000)
        val result = CallSessionResult("request-2", entry.phoneNumber,
            CallSessionResult.Outcome.COMPLETED, entry)
        assertEquals(result, roundTrip(result))
    }

    @Test fun terminalResultsWithoutRecordingPreserveReasonAndRequest() {
        for (outcome in CallSessionResult.Outcome.entries) {
            val result = CallSessionResult("request-$outcome", "13812345678", outcome,
                errorMessage = "结果说明")
            assertEquals(result, roundTrip(result))
        }
    }

    private fun roundTrip(result: CallSessionResult): CallSessionResult {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(result.toBundle())
            parcel.setDataPosition(0)
            CallSessionResult.fromBundle(requireNotNull(parcel.readBundle(javaClass.classLoader)))
        } finally {
            parcel.recycle()
        }
    }
}
