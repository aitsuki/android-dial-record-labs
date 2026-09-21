package com.aitsuki.labs.dialrecord.calllog

import android.content.Context
import android.database.ContentObserver
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.CallLog
import android.telephony.PhoneNumberUtils
import android.util.Log
import androidx.annotation.MainThread
import com.aitsuki.labs.dialrecord.data.RecordingEntry
import com.aitsuki.labs.dialrecord.data.RecordingStore
import com.aitsuki.labs.dialrecord.data.CallLogInfo

private const val TAG = "CallLogMatcher"

/** 只关联刚挂断的这一次通话。立即查询，记录尚未写入时监听变化，最多等待 10 秒。 */
class CallLogMatcher(
    context: Context,
    private val entry: RecordingEntry,
    private val dialRequestedAtMs: Long,
    private val onMatchCompleted: (CallLogInfo?) -> Unit,
) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val mainHandler = Handler(Looper.getMainLooper())
    private val queryThread = HandlerThread("CallLogMatcher").apply { start() }
    private val queryHandler = Handler(queryThread.looper)
    private val queryCancellationSignal = CancellationSignal()
    @Volatile private var closed = false
    private var observing = false
    private val matchTimeout = Runnable {
        Log.w(TAG, "本次通话记录尚未找到，结束关联 file=${entry.fileName}")
        complete(null)
    }
    private val observer = object : ContentObserver(queryHandler) {
        override fun onChange(selfChange: Boolean) {
            Log.d(TAG, "onChange: 通话记录变化，重新查询 file=${entry.fileName}, selfChange=$selfChange")
            query()
        }
    }

    @MainThread
    fun start() {
        if (closed) return
        Log.d(TAG, "start: file=${entry.fileName}, dateRange=${dialRequestedAtMs - 2_000}..${entry.recordingStartedAtMs + 2_000}")
        try {
            // 先监听再查询，避免首次查询与注册监听之间漏掉系统写入事件。
            resolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
            observing = true
            mainHandler.postDelayed(matchTimeout, 10_000)
            queryHandler.post { query() }
        } catch (e: Exception) {
            Log.e(TAG, "无法监听通话记录", e)
            complete(null)
        }
    }

    private fun query() {
        if (closed) return
        Log.d(TAG, "query: file=${entry.fileName}")
        try {
            val match = findMatch() ?: return
            mainHandler.post { complete(match) }
        } catch (e: Exception) {
            if (!closed) {
                Log.e(TAG, "无法读取通话记录", e)
                mainHandler.post { complete(null) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun findMatch(): CallLogInfo? {
        val linkedCallLogIds = RecordingStore.loadEntries(context).mapNotNull { it.callLog?.id }.toSet()
        val matches = mutableListOf<CallLogInfo>()
        resolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DURATION),
            "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} <= ?",
            arrayOf(CallLog.Calls.OUTGOING_TYPE.toString(),
                (dialRequestedAtMs - 2_000).toString(), (entry.recordingStartedAtMs + 2_000).toString()),
            null,
            queryCancellationSignal,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val number = cursor.getString(1).orEmpty()
                if (id in linkedCallLogIds || cursor.isNull(2)) continue
                val duration = cursor.getLong(2)
                if (duration >= 0 && PhoneNumberUtils.compare(entry.phoneNumber, number)) {
                    matches += CallLogInfo(id, duration)
                }
            }
        }
        // 多个候选时不猜测，避免同一号码连续拨打造成错误关联。
        Log.d(TAG, "query: file=${entry.fileName}, matches=$matches")
        return matches.singleOrNull()
    }

    @MainThread
    private fun complete(match: CallLogInfo?) {
        if (closed) return
        Log.d(TAG, "complete: file=${entry.fileName}, match=$match")
        close()
        onMatchCompleted(match)
    }

    @MainThread
    fun close() {
        if (closed) return
        Log.d(TAG, "close: file=${entry.fileName}, observing=$observing")
        closed = true
        mainHandler.removeCallbacks(matchTimeout)
        if (observing) {
            observing = false
            try {
                resolver.unregisterContentObserver(observer)
            } catch (e: Exception) {
                Log.w(TAG, "无法注销通话记录监听", e)
            }
        }
        try {
            queryCancellationSignal.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "无法取消通话记录查询", e)
        }
        queryHandler.removeCallbacksAndMessages(null)
        queryThread.quitSafely()
    }
}
