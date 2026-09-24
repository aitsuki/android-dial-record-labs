package com.aitsuki.labs.dialrecord.data

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordingStoreTest {
    private lateinit var context: Context
    private lateinit var testRoot: File

    @Before fun setup() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        testRoot = File(app.cacheDir, "store-test-" + UUID.randomUUID()).apply { mkdirs() }
        context = object : ContextWrapper(app) {
            override fun getFilesDir() = testRoot
        }
    }

    @After fun cleanup() { testRoot.deleteRecursively() }

    private fun saved(source: CallSource = CallSource.SDK): RecordingEntry {
        val entry = RecordingEntry(UUID.randomUUID().toString(), source, "13800138000", 2000)
        RecordingStore.prepareAudio(context, entry.recordingId).writeText("audio payload")
        return RecordingStore.complete(context, entry)
    }

    @Test fun manualDurationRenamesAudioAndSurvivesReload() {
        val entry = saved()
        val oldFile = RecordingStore.audioFile(context, entry.recordingId)
        val updated = RecordingStore.updateDuration(context, entry.recordingId, 0)
        assertFalse(oldFile.exists())
        assertEquals("13800138000_2000_0.m4a", updated.fileName)
        assertEquals("audio payload", RecordingStore.audioFile(context, entry.recordingId).readText())
        assertEquals(updated, RecordingStore.loadEntries(context).single())
        assertEquals(updated, RecordingStore.updateDuration(context, entry.recordingId, 0))
        assertNull(updated.callLogId)
    }

    @Test fun lateAutomaticMatchDoesNotOverwriteExplicitDuration() {
        val entry = saved(CallSource.SYSTEM)
        RecordingStore.updateDuration(context, entry.recordingId, 12)
        val matched = RecordingStore.attachCallLog(context, entry.recordingId, CallLogInfo(42, 60))
        assertEquals(12L, matched.durationSeconds)
        assertEquals(42L, matched.callLogId)
    }

    @Test fun automaticMatchFillsUnknownDurationIncludingZero() {
        val entry = saved(CallSource.SYSTEM)
        val matched = RecordingStore.attachCallLog(context, entry.recordingId, CallLogInfo(42, 0))
        assertEquals(0L, matched.durationSeconds)
        assertEquals(matched.fileName, RecordingStore.audioFile(context, entry.recordingId).name)
    }

    @Test fun simultaneousMatcherResultsCannotClaimTheSameCall() {
        val first = saved(CallSource.SYSTEM)
        val second = saved(CallSource.SYSTEM)
        RecordingStore.attachCallLog(context, first.recordingId, CallLogInfo(42, 1))
        assertTrue(runCatching {
            RecordingStore.attachCallLog(context, second.recordingId, CallLogInfo(42, 1))
        }.isFailure)
        assertNull(RecordingStore.get(context, second.recordingId)?.callLogId)
    }

    @Test fun interruptedRenameIsRecoveredFromCommittedMetadata() {
        val entry = saved()
        val oldFile = RecordingStore.audioFile(context, entry.recordingId)
        val updated = entry.copy(durationSeconds = 90)
        val metadata = AtomicFile(File(oldFile.parentFile, "entry.json"))
        val stream = metadata.startWrite()
        stream.write(RecordingStore.encode(updated).toByteArray())
        metadata.finishWrite(stream)
        assertEquals(updated, RecordingStore.get(context, entry.recordingId))
        assertFalse(oldFile.exists())
        assertEquals("audio payload", RecordingStore.audioFile(context, entry.recordingId).readText())
    }

    @Test fun renameFailureRestoresOldMetadataAndAudio() {
        val entry = saved()
        val original = RecordingStore.audioFile(context, entry.recordingId)
        val blockedTarget = File(original.parentFile, entry.copy(durationSeconds = 30).fileName)
        assertTrue(blockedTarget.mkdir())
        assertTrue(runCatching { RecordingStore.updateDuration(context, entry.recordingId, 30) }.isFailure)
        assertEquals(entry, RecordingStore.get(context, entry.recordingId))
        assertEquals("audio payload", original.readText())
    }

    @Test fun samePhoneNumberAndTimestampUseIndependentFiles() {
        val first = saved()
        val second = saved()
        assertNotEquals(RecordingStore.audioFile(context, first.recordingId),
            RecordingStore.audioFile(context, second.recordingId))
        assertEquals(2, RecordingStore.loadEntries(context).size)
    }

    @Test fun pendingAudioIsNotPublishedAndDiscardDoesNotDeleteCompletedAudio() {
        val id = UUID.randomUUID().toString()
        val pending = RecordingStore.prepareAudio(context, id).apply { writeText("unfinished") }
        assertTrue(RecordingStore.loadEntries(context).isEmpty())
        RecordingStore.discardPending(context, id)
        assertFalse(pending.exists())
        val entry = saved()
        RecordingStore.discardPending(context, entry.recordingId)
        assertTrue(RecordingStore.audioFile(context, entry.recordingId).exists())
    }

    @Test fun completedPendingAudioIsRecoveredAfterInterruptedPublish() {
        val entry = RecordingEntry(UUID.randomUUID().toString(), CallSource.SDK, "13800138000", 2000)
        val audio = RecordingStore.prepareAudio(context, entry.recordingId).apply { writeText("closed audio") }
        File(audio.parentFile, "entry.json").writeText(RecordingStore.encode(entry))
        assertEquals(entry, RecordingStore.get(context, entry.recordingId))
        assertFalse(audio.exists())
        assertEquals("closed audio", RecordingStore.audioFile(context, entry.recordingId).readText())
    }
}
