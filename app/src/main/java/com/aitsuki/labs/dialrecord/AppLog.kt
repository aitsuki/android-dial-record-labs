package com.aitsuki.labs.dialrecord

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import java.text.SimpleDateFormat
import java.util.Date
import java.util.IllegalFormatException
import java.util.Locale

internal object AppLog {
    private const val TAG = "AppLog"
    private const val MAX_ENTRIES = 300
    private const val MAX_ENTRY_LENGTH = 4000
    private val lock = Any()
    data class Entry(val id: Long, val time: String, val priority: Int, val message: String)
    private var nextEntryId = 0L

    private val entries = ArrayDeque<Entry>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(List<Entry>) -> Unit>()
    private var updatePending = false

    // 监听器仅在主线程注册和通知，不持有已离开首页的 Activity。
    @MainThread
    fun addListener(listener: (List<Entry>) -> Unit) {
        listeners.add(listener)
        listener(synchronized(lock) { entries.toList() })
    }

    @MainThread
    fun removeListener(listener: (List<Entry>) -> Unit) {
        listeners.remove(listener)
    }

    private fun append(priority: Int, text: String) {
        synchronized(lock) {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            entries.addLast(Entry(nextEntryId++, time, priority, text.take(MAX_ENTRY_LENGTH)))
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
            if (updatePending) return
            updatePending = true
        }
        mainHandler.post {
            val snapshot = synchronized(lock) {
                updatePending = false
                entries.toList()
            }
            listeners.toList().forEach { it(snapshot) }
        }
    }

    fun debug(message: String, vararg args: Any?) = log(Log.DEBUG, message, args)
    fun info(message: String, vararg args: Any?) = log(Log.INFO, message, args)
    fun warn(message: String, vararg args: Any?) = log(Log.WARN, message, args)
    fun error(message: String, exception: Throwable? = null, vararg args: Any?) =
        log(Log.ERROR, message, args, exception)

    private fun log(
        priority: Int,
        message: String,
        args: Array<out Any?>,
        exception: Throwable? = null
    ) {
        val text = try {
            if (args.isEmpty()) message else String.format(message, *args)
        } catch (_: IllegalFormatException) {
            message // 日志格式错误不能打断业务流程。
        }
        if (exception == null) {
            Log.println(priority, TAG, singleLine(text))
        } else {
            Log.e(TAG, singleLine(text), exception)
        }
        append(
            priority,
            if (exception == null) singleLine(text)
            else "${singleLine(text)}\n${Log.getStackTraceString(exception)}"
        )
    }

    private fun singleLine(message: String) = message.replace('\r', ' ').replace('\n', ' ')
}
