package com.aitsuki.labs.dialrecord.recording

import android.os.Bundle
import com.aitsuki.labs.dialrecord.data.RecordingEntry
import com.aitsuki.labs.dialrecord.data.RecordingStore

/** 一次拨号请求的终态；COMPLETED 不代表对方已接听。仅在内存中投递。 */
data class CallSessionResult(
    val sessionToken: String,
    val phoneNumber: String,
    val outcome: Outcome,
    val recording: RecordingEntry? = null,
    val errorMessage: String? = null,
) {
    enum class Outcome { COMPLETED, CANCELLED, TIMED_OUT, FAILED }

    internal fun toBundle() = Bundle().apply {
        putString("sessionToken", sessionToken)
        putString("phoneNumber", phoneNumber)
        putString("outcome", outcome.name)
        putString("recording", recording?.let(RecordingStore::encode))
        putString("errorMessage", errorMessage)
    }

    companion object {
        internal fun fromBundle(bundle: Bundle) = CallSessionResult(
            sessionToken = requireNotNull(bundle.getString("sessionToken")),
            phoneNumber = requireNotNull(bundle.getString("phoneNumber")),
            outcome = Outcome.valueOf(requireNotNull(bundle.getString("outcome"))),
            recording = bundle.getString("recording")?.let(RecordingStore::decode),
            errorMessage = bundle.getString("errorMessage"),
        )
    }
}
