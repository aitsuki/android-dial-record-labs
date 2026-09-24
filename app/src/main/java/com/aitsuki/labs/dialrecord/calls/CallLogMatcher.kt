package com.aitsuki.labs.dialrecord.calls

import android.content.Context
import android.provider.CallLog.Calls
import android.telephony.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 这是通话结果，与是否录音无关。date 为毫秒，duration 为秒。 */
data class SystemCallLog(val id: Long, val number: String, val date: Long, val duration: Long, val type: Int)

/** 拨号前记录水位，避免同号码的历史记录被当成本次结果。 */
data class CallLogWindow(val afterId: Long, val fromMs: Long, val toMs: Long) {
    fun contains(id: Long, date: Long) = id > afterId && date in fromMs..toMs
}

object CallLogMatcher {
    suspend fun latestId(context: Context): Long = withContext(Dispatchers.IO) {
        context.contentResolver.query(Calls.CONTENT_URI, arrayOf(Calls._ID), null, null,
            "${Calls._ID} DESC")?.use { if (it.moveToFirst()) it.getLong(0) else 0L }
            ?: error("无法读取系统通话记录")
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
            context.contentResolver.query(Calls.CONTENT_URI,
                arrayOf(Calls._ID, Calls.NUMBER, Calls.DATE, Calls.DURATION, Calls.TYPE),
                "${Calls._ID} > ? AND ${Calls.TYPE} = ? AND ${Calls.DATE} >= ? AND ${Calls.DATE} <= ?",
                arrayOf(window.afterId.toString(), Calls.OUTGOING_TYPE.toString(),
                    window.fromMs.toString(), window.toMs.toString()), null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val record = SystemCallLog(cursor.getLong(0), cursor.getString(1).orEmpty(),
                        cursor.getLong(2), cursor.getLong(3), cursor.getInt(4))
                    if (!cursor.isNull(3) && record.duration >= 0 &&
                        window.contains(record.id, record.date) && PhoneNumberUtils.compare(number, record.number)) {
                        matches += record
                    }
                }
            } ?: error("无法读取系统通话记录")
            matches.singleOrNull()
        }
}
