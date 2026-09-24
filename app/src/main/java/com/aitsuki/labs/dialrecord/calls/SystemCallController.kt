package com.aitsuki.labs.dialrecord.calls

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.aitsuki.labs.dialrecord.data.RecordingEntry
import com.aitsuki.labs.dialrecord.data.RecordingStore

/** 系统电话独有的等待、监听和通话记录补全。不会被 SDK 会话使用。 */
internal class SystemCallController(
    context: Context,
    private val onStarted: () -> Unit,
    private val onEnded: () -> Unit,
    private val onCancelled: (String) -> Unit,
) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var active = false
    var requestedAtMs = 0L
        private set

    private val timeout = Runnable { onCancelled("等待拨号超时") }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!registered) return
            when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_OFFHOOK -> if (!active) {
                    active = true
                    handler.removeCallbacks(timeout)
                    onStarted()
                }
                TelephonyManager.EXTRA_STATE_IDLE -> if (active) onEnded()
                TelephonyManager.EXTRA_STATE_RINGING -> if (!active) onCancelled("来电中断了拨号等待")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun prepare() {
        check(!context.getSystemService(TelecomManager::class.java).isInCall) { "当前已有通话" }
        requestedAtMs = System.currentTimeMillis()
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        registered = true
        handler.postDelayed(timeout, 90_000)
    }

    fun close() {
        handler.removeCallbacks(timeout)
        if (registered) {
            registered = false
            runCatching { context.unregisterReceiver(receiver) }
                .onFailure { Log.w("SystemCallController", "无法注销电话监听", it) }
        }
    }

    companion object {
        /** 可选的进程内补全，不占用录音会话；进程退出时允许保留未知时长。 */
        fun enrich(context: Context, entry: RecordingEntry, requestedAtMs: Long) {
            val app = context.applicationContext
            CallLogMatcher(app, entry, requestedAtMs) { call ->
                if (call != null) {
                    runCatching { RecordingStore.attachCallLog(app, entry.recordingId, call) }
                        .onFailure { Log.w("SystemCallController", "无法补全通话记录", it) }
                }
            }.start()
        }
    }
}
