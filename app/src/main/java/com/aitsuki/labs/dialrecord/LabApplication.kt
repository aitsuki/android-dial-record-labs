package com.aitsuki.labs.dialrecord

import android.app.Application
import android.content.Context
import com.aitsuki.labs.dialrecord.recording.RecordingFiles
import com.aitsuki.labs.dialrecord.recording.RecordingUploader
import java.io.File

class LabApplication : Application() {
    companion object {
        lateinit var app: LabApplication
    }

    lateinit var recordingFiles: RecordingFiles

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        app = this
    }

    override fun onCreate() {
        super.onCreate()
        recordingFiles = RecordingFiles(File(filesDir, "recordings"))
        RecordingUploader(recordingFiles.pending).start()
    }
}
