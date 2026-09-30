package com.aitsuki.labs.dialrecord.recording

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.aitsuki.labs.dialrecord.AppLog


@SuppressLint("AccessibilityPolicy")
class CallAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var isConnected = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLog.debug("CallAccessibilityService connected")
        isConnected = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AppLog.debug("CallAccessibilityService onUnbind")
        isConnected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AppLog.debug("CallAccessibilityService onDestroy")
        isConnected = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}
