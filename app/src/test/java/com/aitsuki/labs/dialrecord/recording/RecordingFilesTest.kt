package com.aitsuki.labs.dialrecord.recording

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dateMs = 1_700_000_000_000L

    private fun sealed(files: RecordingFiles, name: String = "audio.m4a"): File {
        files.staging.mkdirs()
        return File(files.staging, name).apply { writeText("sealed audio") }
    }

    @Test fun filenameUsesProtocolFieldsAndSecondsWithoutUuid() {
        assertEquals("123_+244123456_1700000000_42.m4a",
            RecordingFiles.uploadName("123", "+244123456", dateMs, 42))
        assertEquals("123_123456_1700000000_0.m4a",
            RecordingFiles.uploadName("123", "123456", dateMs, 0))
    }

    @Test fun rejectsUnsafeFieldsUnknownDurationAndUnalignedDate() {
        for (userId in listOf("", "../a", "a_b", "a/b")) {
            assertThrows(IllegalArgumentException::class.java) {
                RecordingFiles.uploadName(userId, "123", dateMs, 1)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecordingFiles.uploadName("lab", "123", dateMs, -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecordingFiles.uploadName("lab", "123", dateMs + 1, 1)
        }
    }

    @Test fun publishMovesSealedAudioIntoPendingDirectory() {
        val files = RecordingFiles(temporary.root)
        val audio = sealed(files)
        assertFalse(files.pending.exists())
        val published = files.publish(audio, "lab", "123", dateMs, 5)
        assertFalse(audio.exists())
        assertEquals(files.pending, published.parentFile)
        assertEquals("sealed audio", published.readText())
        assertTrue(RecordingFiles.isUploadFile(published))
    }

    @Test fun partAndEmptyFilesCannotBePublished() {
        val files = RecordingFiles(temporary.root)
        val part = files.create().apply { writeText("still recording") }
        assertThrows(IllegalStateException::class.java) { files.publish(part, "lab", "123", dateMs, 1) }
        val empty = sealed(files).apply { writeText("") }
        assertThrows(IllegalStateException::class.java) { files.publish(empty, "lab", "123", dateMs, 1) }
        assertTrue(part.exists())
        assertTrue(empty.exists())
    }

    @Test fun filenameCollisionPreservesBothFilesInsteadOfOverwriting() {
        val files = RecordingFiles(temporary.root)
        val first = files.publish(sealed(files), "lab", "123", dateMs, 1)
        val second = sealed(files, "second.m4a").apply { writeText("another call") }
        assertThrows(IllegalStateException::class.java) { files.publish(second, "lab", "123", dateMs, 1) }
        assertEquals("sealed audio", first.readText())
        assertEquals("another call", second.readText())
    }
}
