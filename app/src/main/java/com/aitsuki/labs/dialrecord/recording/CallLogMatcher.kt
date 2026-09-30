package com.aitsuki.labs.dialrecord.recording

import android.content.Context
import android.os.SystemClock
import android.provider.CallLog.Calls
import com.aitsuki.labs.dialrecord.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

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
        )?.use { if (it.moveToFirst()) it.getLong(0) else 0L }
            ?: error("Unable to read the system call log")
    }

    /** 系统写入记录可能延迟。限时查询；没有匹配或多个候选都不猜测。 */
    suspend fun await(context: Context, number: String, window: CallLogWindow): SystemCallLog? {
        val startedAt = SystemClock.elapsedRealtime()
        var attempts = 0
        val result = withTimeoutOrNull(10_000.milliseconds) {
            var match: SystemCallLog? = null
            while (match == null) {
                attempts++
                match = query(context, number, window)
                if (match == null) {
                    delay(500.milliseconds)
                }
            }
            match
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        if (result != null) {
            AppLog.info(
                "已匹配系统通话记录：号码=%s，记录 ID=%s，时长=%s 秒，查询次数=%s，耗时=%s 毫秒",
                number, result.id, result.duration, attempts, elapsedMs
            )
        } else {
            AppLog.info(
                "系统通话记录匹配超时，没有唯一匹配结果：号码=%s，查询次数=%s，耗时=%s 毫秒",
                number, attempts, elapsedMs
            )
        }
        return result
    }

    /** 忽略号码格式，匹配后 6 位；短号码完整匹配。 */
    private fun matchesPhoneNumber(first: String, second: String): Boolean {
        val a = first.filter { it in '0'..'9' }
        val b = second.filter { it in '0'..'9' }
        if (a.isEmpty() || b.isEmpty()) return false
        return if (a.length >= 6 && b.length >= 6) {
            a.takeLast(6) == b.takeLast(6)
        } else {
            a == b
        }
    }

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
                    if (!cursor.isNull(3) && record.duration >= 0 &&
                        window.contains(record.id, record.date) && matchesPhoneNumber(
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
