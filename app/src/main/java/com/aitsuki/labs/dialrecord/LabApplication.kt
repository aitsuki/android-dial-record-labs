package com.aitsuki.labs.dialrecord

import android.app.Application
import android.util.Log
import com.aitsuki.labs.dialrecord.recording.RecordingFiles
import com.aitsuki.labs.dialrecord.upload.RecordingUploader
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LabApplication : Application() {
    lateinit var recordings: RecordingFiles
        private set
    private val uploadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        recordings = RecordingFiles(File(filesDir, "recordings"))
        RecordingUploader(recordings.pending, ::uploadRecording) { file, error ->
            Log.w("RecordingUploader", "上传保留待重试：${file?.name}", error)
        }.start(uploadScope)
    }

    /**
     * 线上移植点：以 file.name 作为 multipart filename，收到业务成功确认后才返回 true。
     * 实验项目尚未配置服务器/认证，不能伪造成功，更不能因此删除本地音频。
     * 网络实现应有超时、支持取消；CancellationException 必须继续抛出。
     */
    private suspend fun uploadRecording(file: File): Boolean {
        Log.i("RecordingUploader", "上传接口未接入，保留文件：${file.name}")
        return false
    }
}
