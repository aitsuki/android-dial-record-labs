package com.aitsuki.labs.dialrecord.upload

import com.aitsuki.labs.dialrecord.recording.RecordingFiles
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingUploaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun pending(name: String = "lab_123_1700000000_5.m4a") =
        File(temporary.root, name).apply { writeText("audio") }

    @Test fun onlyAcknowledgedFileIsDeletedAndFailureDoesNotBlockOthers() = runBlocking {
        val rejected = pending("a_123_1700000000_1.m4a")
        val failed = pending("b_123_1700000000_1.m4a")
        val accepted = pending("c_123_1700000000_1.m4a")
        val errors = mutableListOf<File?>()
        val uploader = RecordingUploader(temporary.root, { file ->
            when (file) {
                rejected -> false
                failed -> throw IOException("offline")
                else -> true
            }
        }, { file, _ -> errors += file })
        uploader.uploadPending()
        assertTrue(rejected.exists())
        assertTrue(failed.exists())
        assertFalse(accepted.exists())
        assertEquals(listOf(failed), errors)
    }

    @Test fun newProcessInstanceRetriesSameFilename() = runBlocking {
        val file = pending()
        val names = mutableListOf<String>()
        RecordingUploader(temporary.root, { names += it.name; false }, { _, _ -> }).uploadPending()
        assertTrue(file.exists())
        RecordingUploader(temporary.root, { names += it.name; true }, { _, _ -> }).uploadPending()
        assertEquals(listOf(file.name, file.name), names)
        assertFalse(file.exists())
    }

    @Test fun ignoresStagingPartEmptyAndMalformedFiles() = runBlocking {
        val files = RecordingFiles(temporary.root)
        val part = files.create().apply { writeText("recording") }
        val sealed = File(files.staging, "sealed.m4a").apply { writeText("sealed but not associated") }
        files.pending.mkdirs()
        File(files.pending, "lab_123_1700000000_1.part").writeText("unfinished")
        File(files.pending, "unknown.m4a").writeText("missing fields")
        File(files.pending, "lab_123_1700000000_1.m4a").writeText("")
        var uploads = 0
        RecordingUploader(files.pending, { uploads++; true }, { _, _ -> }).uploadPending()
        assertEquals(0, uploads)
        assertTrue(part.exists())
        assertTrue(sealed.exists())
    }

    @Test fun cancellationIsNotSwallowedAndDoesNotDeleteFile() = runBlocking {
        val file = pending()
        var cancelled = false
        try {
            RecordingUploader(temporary.root, { throw CancellationException("cancelled") }, { _, _ ->
                fail("Cancellation must propagate")
            }).uploadPending()
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertTrue(file.exists())
    }

    @Test fun cancelledAfterServerSuccessStillRetainsFile() = runBlocking {
        val file = pending()
        val worker = launch {
            RecordingUploader(temporary.root, {
                currentCoroutineContext().job.cancel()
                true
            }, { _, _ -> fail("Cancellation must propagate") }).uploadPending()
        }
        worker.join()
        assertTrue(file.exists())
    }

    @Test fun failedUploadIsRetriedOnNextPoll() = runBlocking {
        val file = pending()
        var attempts = 0
        val uploader = RecordingUploader(temporary.root, { ++attempts >= 2 }, { _, _ -> })
        val job = uploader.start(this, intervalMs = 10)
        try {
            withTimeout(3_000) { while (file.exists()) delay(5) }
            assertEquals(2, attempts)
        } finally { job.cancelAndJoin() }
    }

    @Test fun startsOneLoopAndScansImmediately() = runBlocking {
        val file = pending()
        val entered = CompletableDeferred<Unit>()
        val uploader = RecordingUploader(temporary.root, {
            entered.complete(Unit)
            CompletableDeferred<Boolean>().await()
        }, { _, _ -> })
        val job = uploader.start(this)
        try {
            assertSame(job, uploader.start(this))
            withTimeout(3_000) { entered.await() }
        } finally { job.cancelAndJoin() }
        assertTrue(file.exists())
    }
}
