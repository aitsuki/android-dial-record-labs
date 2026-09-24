package com.aitsuki.labs.dialrecord.calls

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout

/** 一次系统拨号。只负责电话状态，不依赖录音服务；调用者取消时立即注销监听。 */
object SystemCallController {
    data class Window(val requestedAtMs: Long, val offhookAtMs: Long)

    @SuppressLint("MissingPermission")
    suspend fun call(context: Context, number: String, onOffhook: () -> Unit): Window {
        check(!context.getSystemService(TelecomManager::class.java).isInCall) { "当前已有通话" }
        val states = Channel<String>(Channel.UNLIMITED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                intent.getStringExtra(TelephonyManager.EXTRA_STATE)?.let { states.trySend(it) }
            }
        }
        ContextCompat.registerReceiver(context, receiver,
            IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        try {
            val requestedAt = System.currentTimeMillis()
            context.startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)))
            // OFFHOOK 包含拨号阶段，不代表对方接听。尚未拨出时的初始 IDLE 要忽略。
            withTimeout(90_000) {
                var offhook = false
                while (!offhook) {
                    when (states.receive()) {
                        TelephonyManager.EXTRA_STATE_OFFHOOK -> offhook = true
                        TelephonyManager.EXTRA_STATE_RINGING -> error("来电中断了拨号等待")
                    }
                }
            }
            val offhookAt = System.currentTimeMillis()
            onOffhook()
            while (states.receive() != TelephonyManager.EXTRA_STATE_IDLE) { /* 等待挂断 */ }
            return Window(requestedAt, offhookAt)
        } finally {
            context.unregisterReceiver(receiver)
            states.close()
        }
    }
}
