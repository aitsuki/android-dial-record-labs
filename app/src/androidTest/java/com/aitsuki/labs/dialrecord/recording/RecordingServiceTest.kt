package com.aitsuki.labs.dialrecord.recording

import android.Manifest
import android.media.MediaExtractor
import android.os.ParcelFileDescriptor
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aitsuki.labs.dialrecord.ui.MainActivity
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 真机麦克风验证，不拨打电话，也不模拟 Infobip 双向音频。 */
@RunWith(AndroidJUnit4::class)
class RecordingServiceTest {
    @Before fun grantMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val command = "pm grant ${instrumentation.targetContext.packageName} ${Manifest.permission.RECORD_AUDIO}"
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
    }

    @Test fun recordingProducesAudioAndFinishIsIdempotent() {
        val done = CompletableFuture<RecordingResult>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.lifecycleScope.launch {
                    try {
                        RecordingService.withRecorder(activity) { recorder ->
                            recorder.start()
                            delay(1_200)
                            val result = recorder.finish()
                            assertEquals(result, recorder.finish())
                            done.complete(result)
                        }
                    } catch (e: Throwable) { done.completeExceptionally(e) }
                }
            }
            val result = done.get(15, TimeUnit.SECONDS)
            assertNull(result.error)
            val file = requireNotNull(result.file)
            try {
                assertEquals("m4a", file.extension)
                assertEquals("staging", file.parentFile?.name)
                assertNotNull(result.startedAtMs)
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    assertTrue(extractor.trackCount > 0)
                    assertTrue(extractor.getTrackFormat(0).getString("mime")!!.startsWith("audio/"))
                } finally { extractor.release() }
            } finally { file.delete() }
        }
    }

    @Test fun destroyingPageCleansUpWithoutCompletingRequest() {
        val started = CompletableFuture<Unit>()
        val cleaned = CompletableFuture<RecordingResult>()
        var callbackCount = 0
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.lifecycleScope.launch {
                    var service: RecordingService? = null
                    try {
                        RecordingService.withRecorder(activity) { recorder ->
                            service = recorder
                            recorder.start()
                            delay(1_200)
                            started.complete(Unit)
                            CompletableDeferred<Unit>().await()
                        }
                        callbackCount++
                    } finally {
                        cleaned.complete(service?.finish() ?: RecordingResult(error = "未连接"))
                    }
                }
            }
            started.get(15, TimeUnit.SECONDS)
        } finally { scenario.close() }
        val result = cleaned.get(15, TimeUnit.SECONDS)
        try {
            assertEquals(0, callbackCount)
            assertNull(result.error)
            assertTrue(requireNotNull(result.file).isFile)
            assertEquals("staging", result.file?.parentFile?.name)
        } finally { result.file?.delete() }
    }
}
