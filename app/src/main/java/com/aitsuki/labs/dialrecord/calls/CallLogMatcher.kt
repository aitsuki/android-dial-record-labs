package com.aitsuki.labs.dialrecord.calls

import android.content.Context
import android.provider.CallLog.Calls
import android.telephony.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 这是通话结果，与是否录音无关。date 为毫秒，duration 为秒。 */
data class SystemCallLog(
    val id: Long,
    val number: String,
    val date: Long,
    val duration: Long,
    val type: Int
)

/** 拨号前记录水位，避免同号码的历史记录被当成本次结果。 */
data class CallLogWindow(val afterId: Long, val fromMs: Long, val toMs: Long) {
    fun contains(id: Long, date: Long) = id > afterId && date in fromMs..toMs
}

object CallLogMatcher {
    suspend fun latestId(context: Context): Long = withContext(Dispatchers.IO) {
        context.contentResolver.query(
            Calls.CONTENT_URI, arrayOf(Calls._ID), null, null,
            "${Calls._ID} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Calls._ID)
            if (cursor.moveToFirst()) cursor.getLong(idIndex) else 0L
        }
            ?: error("Unable to read the system call log")
    }

    /** 系统写入记录可能延迟。限时查询；没有匹配或多个候选都不猜测。 */
    suspend fun await(context: Context, number: String, window: CallLogWindow): SystemCallLog? =
        withTimeoutOrNull(10_000) {
            var match: SystemCallLog? = null
            while (match == null) {
                match = query(context, number, window)
                if (match == null) delay(500)
            }
            match
        }

    @Suppress("DEPRECATION")
    private suspend fun query(context: Context, number: String, window: CallLogWindow) =
        withContext(Dispatchers.IO) {
            val matches = mutableListOf<SystemCallLog>()
            context.contentResolver.query(
                Calls.CONTENT_URI,
                arrayOf(Calls._ID, Calls.NUMBER, Calls.DATE, Calls.DURATION, Calls.TYPE),
                "${Calls._ID} > ? AND ${Calls.TYPE} = ? AND ${Calls.DATE} >= ? AND ${Calls.DATE} <= ?",
                arrayOf(
                    window.afterId.toString(), Calls.OUTGOING_TYPE.toString(),
                    window.fromMs.toString(), window.toMs.toString()
                ), null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(Calls._ID)
                val numberIndex = cursor.getColumnIndexOrThrow(Calls.NUMBER)
                val dateIndex = cursor.getColumnIndexOrThrow(Calls.DATE)
                val durationIndex = cursor.getColumnIndexOrThrow(Calls.DURATION)
                val typeIndex = cursor.getColumnIndexOrThrow(Calls.TYPE)
                while (cursor.moveToNext()) {
                    val record = SystemCallLog(
                        id = cursor.getLong(idIndex),
                        number = cursor.getString(numberIndex).orEmpty(),
                        date = cursor.getLong(dateIndex),
                        duration = cursor.getLong(durationIndex),
                        type = cursor.getInt(typeIndex)
                    )
                    if (!cursor.isNull(durationIndex) && record.duration >= 0 &&
                        window.contains(record.id, record.date) && PhoneNumberUtils.compare(
                            number,
                            record.number
                        )
                    ) {
                        matches += record
                    }
                }
            } ?: error("Unable to read the system call log")
            matches.singleOrNull()
        }
}
