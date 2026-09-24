package com.aitsuki.labs.dialrecord.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLogWindowTest {
    private val window = CallLogWindow(afterId = 100, fromMs = 10_000, toMs = 20_000)

    @Test fun excludesOldRecordsEvenWhenTheirDateMatches() {
        assertFalse(window.contains(99, 15_000))
        assertFalse(window.contains(100, 15_000))
    }

    @Test fun excludesNewRecordsOutsideThisDialWindow() {
        assertFalse(window.contains(101, 9_999))
        assertFalse(window.contains(101, 20_001))
    }

    @Test fun acceptsNewRecordsWithinInclusiveWindow() {
        assertTrue(window.contains(101, 10_000))
        assertTrue(window.contains(102, 15_000))
        assertTrue(window.contains(103, 20_000))
    }
}
