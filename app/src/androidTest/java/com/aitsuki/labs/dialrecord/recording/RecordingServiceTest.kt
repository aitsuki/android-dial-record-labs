package com.aitsuki.labs.dialrecord.recording

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aitsuki.labs.dialrecord.data.CallSource
import com.aitsuki.labs.dialrecord.data.RecordingEntry
import com.aitsuki.labs.dialrecord.data.RecordingStore
import com.aitsuki.labs.dialrecord.ui.MainActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RecordingServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private lateinit var activity: ActivityScenario<MainActivity>
    private val ids = mutableListOf<String>()

    private class Events : android.os.ResultReceiver(Handler(Looper.getMainLooper())) {
        private val results = LinkedBlockingQueue<RecordingResult>()
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            results.put(RecordingResult.fromBundle(requireNotNull(resultData)))
        }
        fun next(event: RecordingResult.Event): RecordingResult {
            val result = requireNotNull(results.poll(10, TimeUnit.SECONDS)) { "没有收到 $event" }
            assertEquals(result.errorMessage, event, result.event)
            return result
        }
        fun hasNoMoreEvents() = results.poll(300, TimeUnit.MILLISECONDS) == null
    }

    @Before fun setup() {
        for (permission in RecordingService.requiredPermissions) {
            val descriptor = instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        activity = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun cleanup() {
        RecordingService.session?.let { RecordingService.finish(context, it.recordingId) }
        instrumentation.waitForIdleSync()
        activity.close()
        for (id in ids) File(context.filesDir, "recordings/$id").deleteRecursively()
    }

    private fun prepare(source: CallSource, events: Events): String {
        val id = UUID.randomUUID().toString().also { ids += it }
        RecordingService.prepare(context, id, source, "13812345678", events)
        assertEquals(id, events.next(RecordingResult.Event.READY).recordingId)
        return id
    }

    @Test fun sdkRecordingHandlesDuplicateStartAndStaleCommandsAndSavesSuppliedDuration() {
        val events = Events()
        val id = prepare(CallSource.SDK, events)
        RecordingService.start(context, id)
        events.next(RecordingResult.Event.STARTED)
        RecordingService.start(context, id)
        RecordingService.cancelPreparation(context, id)
        RecordingService.finish(context, UUID.randomUUID().toString())
        SystemClock.sleep(1500) // MediaRecorder needs encoded samples before stop().
        assertEquals(RecordingService.Phase.RECORDING, RecordingService.session?.phase)
        RecordingService.setDuration(context, id, 7)
        RecordingService.finish(context, id)
        val result = events.next(RecordingResult.Event.COMPLETED)
        assertEquals(7L, result.recording?.durationSeconds)
        assertNull(result.recording?.callLogId)
        assertNull(RecordingService.session)
        val file = RecordingStore.audioFile(context, id)
        assertTrue(file.name.endsWith("_7.m4a"))
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            assertTrue(requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong() > 0)
        } finally { retriever.release() }
        RecordingService.finish(context, id)
        assertTrue(events.hasNoMoreEvents())
    }

    @Test fun busyRequestDoesNotReplaceSessionAndSystemPreparationCanBeCancelledWithoutDialling() {
        val events = Events()
        val id = prepare(CallSource.SYSTEM, events)
        val rejected = Events()
        val otherId = UUID.randomUUID().toString()
        RecordingService.prepare(context, otherId, CallSource.SDK, "13800138000", rejected)
        rejected.next(RecordingResult.Event.FAILED)
        assertEquals(id, RecordingService.session?.recordingId)
        RecordingService.start(context, id) // 系统模式只能由电话事件启动。
        instrumentation.waitForIdleSync()
        assertEquals(RecordingService.Phase.READY, RecordingService.session?.phase)
        RecordingService.cancelPreparation(context, id)
        events.next(RecordingResult.Event.CANCELLED)
        assertNull(RecordingService.session)
        assertNull(RecordingStore.get(context, id))
    }

    @Test fun resultPreservesStableIdZeroDurationAndOptionalCallLogAcrossParcel() {
        val id = UUID.randomUUID().toString()
        val entry = RecordingEntry(id, CallSource.SDK, "13800138000", 2000, 0)
        val expected = RecordingResult(id, RecordingResult.Event.COMPLETED, entry)
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(expected.toBundle())
            parcel.setDataPosition(0)
            assertEquals(expected, RecordingResult.fromBundle(requireNotNull(parcel.readBundle(javaClass.classLoader))))
        } finally { parcel.recycle() }
    }
}
