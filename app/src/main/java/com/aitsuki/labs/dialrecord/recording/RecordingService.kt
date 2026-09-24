package com.aitsuki.labs.dialrecord.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.aitsuki.labs.dialrecord.R
import com.aitsuki.labs.dialrecord.ui.MainActivity
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

data class RecordingResult(val file: File? = null, val error: String? = null, val startedAtMs: Long? = null)

/** 只负责麦克风和前台通知。不监听电话，不查询通话记录，不持久化会话。主线程调用。 */
class RecordingService : Service() {
    private inner class LocalBinder : Binder() {
        val service get() = this@RecordingService
    }

    companion object {
        /** 绑定属于本次请求。页面销毁/协程取消时停止录音并解绑，不恢复请求。 */
        suspend fun <T> withRecorder(context: Context, block: suspend (RecordingService) -> T): T {
            val ready = CompletableDeferred<RecordingService>()
            var service: RecordingService? = null
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    service = (binder as LocalBinder).service
                    ready.complete(requireNotNull(service))
                }
                override fun onServiceDisconnected(name: ComponentName) {
                    service?.fail("录音服务连接中断")
                    ready.completeExceptionally(IllegalStateException("录音服务连接中断"))
                }
                override fun onNullBinding(name: ComponentName) {
                    ready.completeExceptionally(IllegalStateException("无法绑定录音服务"))
                }
                override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
            }
            var bound = false
            var ownsRecorder = false
            try {
                bound = context.bindService(Intent(context, RecordingService::class.java), connection, Context.BIND_AUTO_CREATE)
                check(bound) { "无法绑定录音服务" }
                val recorder = withTimeout(10_000) { ready.await() }
                // 必须在页面可见、发起系统拨号之前建立麦克风前台服务。
                if (context is LifecycleOwner) {
                    check(context.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { "页面已离开，请重新发起通话" }
                }
                check(!recorder.inUse) { "已有录音请求" }
                recorder.inUse = true
                ownsRecorder = true
                recorder.foreground()
                return block(recorder)
            } finally {
                if (ownsRecorder) {
                    service?.finish()
                    service?.inUse = false
                }
                if (bound) context.unbindService(connection)
            }
        }
    }

    private var inUse = false
    private var recorder: MediaRecorder? = null
    private var pendingFile: File? = null
    private var started = false
    private var finished = false
    private var result = RecordingResult()

    override fun onBind(intent: Intent): IBinder = LocalBinder()

    private fun foreground() {
        // 上次解绑后系统可能尚未销毁实例；新请求只复用服务，不复用录音结果。
        check(recorder == null) { "已有录音正在进行" }
        pendingFile = null
        started = false
        finished = false
        result = RecordingResult()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("recording", "通话录音验证", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0,
            Intent.makeMainActivity(ComponentName(this, MainActivity::class.java)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "recording")
            .setSmallIcon(R.drawable.ic_recording).setContentTitle("通话录音验证")
            .setContentText("本次请求的录音服务已就绪").setContentIntent(open).setOngoing(true).build()
        ServiceCompat.startForeground(this, 1, notification,
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
    }

    /** 失败记录在结果中，不抛出以打断通话结果的等待。 */
    fun start() {
        if (recorder != null || finished) return
        try {
            val file = RecordingFiles(File(filesDir, "recordings")).create()
            pendingFile = file
            @Suppress("DEPRECATION")
            val media = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            recorder = media
            media.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            media.setAudioEncodingBitRate(128_000)
            media.setAudioSamplingRate(44_100)
            media.setOutputFile(file.absolutePath)
            media.setOnErrorListener { _, what, extra -> fail("录音器错误：$what/$extra") }
            media.prepare()
            val startedAtMs = System.currentTimeMillis()
            media.start()
            started = true
            result = result.copy(startedAtMs = startedAtMs)
        } catch (e: Exception) {
            fail("无法启动录音：${e.message}")
        }
    }

    private fun fail(message: String) {
        result = result.copy(error = message)
        finish()
    }

    /** 幂等结束；正常封装后仍留在 staging，不能直接上传。 */
    fun finish(): RecordingResult {
        if (finished) return result
        finished = true
        val media = recorder
        recorder = null
        try {
            if (started) media?.stop()
        } catch (e: Exception) {
            result = result.copy(error = "无法结束录音：${e.message}")
        } finally {
            runCatching { media?.release() }.onFailure {
                result = result.copy(error = "无法释放录音器：${it.message}")
            }
        }
        pendingFile?.let { file ->
            val target = File(file.parentFile, "${file.nameWithoutExtension}.m4a")
            if (started && result.error == null && file.length() > 0 && file.renameTo(target)) {
                result = result.copy(file = target)
            } else {
                file.delete()
                if (result.error == null) result = RecordingResult(error = "未生成有效录音文件")
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        return result
    }

    override fun onDestroy() {
        finish()
        super.onDestroy()
    }
}
