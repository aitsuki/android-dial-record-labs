package com.aitsuki.labs.dialrecord.recording

import android.Manifest.permission.CALL_PHONE
import android.Manifest.permission.READ_CALL_LOG
import android.Manifest.permission.READ_PHONE_STATE
import android.Manifest.permission.RECORD_AUDIO
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.aitsuki.labs.dialrecord.R
import com.aitsuki.labs.dialrecord.calls.SystemCallController
import com.aitsuki.labs.dialrecord.data.CallSource
import com.aitsuki.labs.dialrecord.data.PHONE_NUMBER_PATTERN
import com.aitsuki.labs.dialrecord.data.RecordingEntry
import com.aitsuki.labs.dialrecord.data.RecordingStore
import com.aitsuki.labs.dialrecord.ui.MainActivity
import java.util.UUID

/** READY 只表示服务已就绪；STARTED 才表示录音器成功启动。COMPLETED 不表示电话已接听。 */
data class RecordingResult(
    val recordingId: String,
    val event: Event,
    val recording: RecordingEntry? = null,
    val errorMessage: String? = null,
) {
    enum class Event { READY, STARTED, COMPLETED, CANCELLED, FAILED }

    internal fun toBundle() = Bundle().apply {
        putString("recordingId", recordingId)
        putString("event", event.name)
        putString("recording", recording?.let(RecordingStore::encode))
        putString("errorMessage", errorMessage)
    }

    companion object {
        fun fromBundle(bundle: Bundle) = RecordingResult(
            requireNotNull(bundle.getString("recordingId")),
            Event.valueOf(requireNotNull(bundle.getString("event"))),
            bundle.getString("recording")?.let(RecordingStore::decode),
            bundle.getString("errorMessage"),
        )
    }
}

/**
 * 通用录音生命周期与前台服务。所有命令在主线程串行处理，并按 recordingId 拒绝过期命令。
 * SDK 接入层：prepare → READY → start → finish；通话时长可以在 finish 时传入或事后更新 Store。
 * SYSTEM 使用 SystemCallController 驱动同一套录音操作。
 */
class RecordingService : Service() {
    enum class Phase { PREPARING, READY, RECORDING, STOPPING }
    data class Session(val recordingId: String, val source: CallSource, val phoneNumber: String, val phase: Phase)

    companion object {
        private const val TAG = "RecordingService"
        private const val PREPARE = "recording.PREPARE"
        private const val START = "recording.START"
        private const val FINISH = "recording.FINISH"
        private const val CANCEL = "recording.CANCEL"
        private const val DURATION = "recording.DURATION"
        private const val ID = "recordingId"
        private const val RECEIVER = "receiver"
        const val RESULT_EVENT = 1

        internal val requiredPermissions = listOf(RECORD_AUDIO, CALL_PHONE, READ_PHONE_STATE, READ_CALL_LOG)

        @Volatile
        var session: Session? = null
            private set

        val hasSession: Boolean get() = session != null

        /** 在可见页面准备前台服务；收到 READY 后再拨号或启动 SDK。 */
        fun prepare(context: Context, id: String, source: CallSource, phoneNumber: String, receiver: ResultReceiver) {
            require(UUID.fromString(id).toString() == id)
            ContextCompat.startForegroundService(context,
                command(context, PREPARE, id)
                    .putExtra("source", source.name).putExtra("phoneNumber", phoneNumber)
                    .putExtra(RECEIVER, receiver))
        }

        fun start(context: Context, id: String) { context.startService(command(context, START, id)) }

        /** null 保留当前时长；未知时长不会拿录音长度代替。 */
        fun finish(context: Context, id: String, durationSeconds: Long? = null) {
            require(durationSeconds == null || durationSeconds >= 0)
            context.startService(command(context, FINISH, id).apply {
                durationSeconds?.let { putExtra("duration", it) }
            })
        }

        /** 仅取消尚未开始录音的请求，避免页面销毁误停正在进行的录音。 */
        fun cancelPreparation(context: Context, id: String) {
            context.startService(command(context, CANCEL, id))
        }

        /** 录音期间暂存时长，结束后统一命名；已完成的录音使用 RecordingStore.updateDuration。 */
        fun setDuration(context: Context, id: String, seconds: Long) {
            require(seconds >= 0)
            context.startService(command(context, DURATION, id).putExtra("duration", seconds))
        }

        private fun command(context: Context, action: String, id: String) =
            Intent(context, RecordingService::class.java).setAction(action).putExtra(ID, id)
    }

    private var receiver: ResultReceiver? = null
    private var systemCall: SystemCallController? = null
    private var recorder: MediaRecorder? = null
    private var entry: RecordingEntry? = null
    private var durationSeconds: Long? = null
    private var preparedAudio = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("recording", "录音", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(ID)
        if (intent?.action == PREPARE) {
            prepareSession(intent)
        } else if (id != null && session?.recordingId == id) {
            when (intent.action) {
                START -> if (session?.source != CallSource.SYSTEM) startRecording()
                FINISH -> {
                    if (intent.hasExtra("duration")) {
                        val seconds = intent.getLongExtra("duration", -1)
                        if (seconds < 0) return START_NOT_STICKY
                        durationSeconds = seconds
                    }
                    finishSession()
                }
                CANCEL -> if (session?.phase == Phase.READY) {
                    finishSession(RecordingResult.Event.CANCELLED)
                }
                DURATION -> if (intent.hasExtra("duration")) {
                    val seconds = intent.getLongExtra("duration", -1)
                    if (seconds >= 0) durationSeconds = seconds
                }
            }
        }
        if (session == null) stopSelfResult(startId)
        return START_NOT_STICKY
    }

    private fun prepareSession(intent: Intent) {
        val requestedReceiver = IntentCompat.getParcelableExtra(intent, RECEIVER, ResultReceiver::class.java)
        val id = intent.getStringExtra(ID).orEmpty()
        if (hasSession) {
            send(requestedReceiver, RecordingResult(id, RecordingResult.Event.FAILED,
                errorMessage = "已有录音会话，请先结束当前会话"))
            return
        }
        try {
            require(UUID.fromString(id).toString() == id) { "录音 ID 无效" }
            check(requiredPermissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) { "录音需要电话、电话状态、通话记录及麦克风权限" }
            val source = CallSource.valueOf(requireNotNull(intent.getStringExtra("source")))
            val phoneNumber = intent.getStringExtra("phoneNumber").orEmpty().trim()
            require(PHONE_NUMBER_PATTERN.matches(phoneNumber)) { "电话号码无效" }
            check(RecordingStore.get(this, id) == null) { "录音 ID 已使用" }
            receiver = requestedReceiver
            session = Session(id, source, phoneNumber, Phase.PREPARING)
            notifyForeground("正在准备录音")
            if (source == CallSource.SYSTEM) {
                systemCall = SystemCallController(this,
                    onStarted = { startRecording() },
                    onEnded = { finishSession(matchSystemCall = true) },
                    onCancelled = { finishSession(RecordingResult.Event.CANCELLED, it) },
                ).also { it.prepare() }
            }
            session = requireNotNull(session).copy(phase = Phase.READY)
            notifyForeground(if (source == CallSource.SYSTEM) "等待系统通话" else "录音已就绪")
            send(receiver, RecordingResult(id, RecordingResult.Event.READY))
        } catch (error: Exception) {
            Log.e(TAG, "无法准备录音", error)
            if (session != null) {
                finishSession(RecordingResult.Event.FAILED, error.message)
            } else {
                send(requestedReceiver, RecordingResult(id, RecordingResult.Event.FAILED,
                    errorMessage = error.message))
            }
        }
    }

    private fun startRecording() {
        val current = session ?: return
        if (current.phase != Phase.READY) return
        try {
            val audio = RecordingStore.prepareAudio(this, current.recordingId)
            preparedAudio = true
            @Suppress("DEPRECATION")
            val media = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            recorder = media
            media.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            media.setAudioEncodingBitRate(128_000)
            media.setAudioSamplingRate(44_100)
            media.setOutputFile(audio.absolutePath)
            media.prepare()
            media.start()
            entry = RecordingEntry(current.recordingId, current.source, current.phoneNumber, System.currentTimeMillis())
            session = current.copy(phase = Phase.RECORDING)
            notifyForeground("正在录音")
            send(receiver, RecordingResult(current.recordingId, RecordingResult.Event.STARTED))
        } catch (error: Exception) {
            Log.e(TAG, "无法启动录音", error)
            finishSession(RecordingResult.Event.FAILED, "无法启动录音：${error.message}")
        }
    }

    private fun finishSession(
        event: RecordingResult.Event = RecordingResult.Event.COMPLETED,
        error: String? = null,
        matchSystemCall: Boolean = false,
    ) {
        val current = session ?: return
        if (current.phase == Phase.STOPPING) return
        session = current.copy(phase = Phase.STOPPING)
        val requestedAtMs = systemCall?.requestedAtMs
        systemCall?.close()
        systemCall = null
        var saved: RecordingEntry? = null
        var failure = error
        val media = recorder
        recorder = null
        try {
            if (media != null) {
                try {
                    if (entry != null) media.stop()
                } finally {
                    media.release()
                }
            }
            entry?.let { saved = RecordingStore.complete(this, it.copy(durationSeconds = durationSeconds)) }
        } catch (problem: Exception) {
            failure = "无法保存录音：${problem.message}"
            Log.e(TAG, failure, problem)
        }
        if (preparedAudio && saved == null) {
            runCatching { RecordingStore.discardPending(this, current.recordingId) }
                .onFailure { Log.w(TAG, "无法清理未完成音频", it) }
        }
        val resultEvent = when {
            failure != null && event != RecordingResult.Event.CANCELLED -> RecordingResult.Event.FAILED
            event != RecordingResult.Event.COMPLETED -> event
            saved == null -> RecordingResult.Event.CANCELLED
            else -> RecordingResult.Event.COMPLETED
        }
        val callback = receiver
        receiver = null
        entry = null
        durationSeconds = null
        preparedAudio = false
        session = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        send(callback, RecordingResult(current.recordingId, resultEvent, saved, failure))

        // 补全只持有 applicationContext 和已完成录音，不阻塞下一次录音，不保留 Service。
        if (matchSystemCall && saved != null && requestedAtMs != null) {
            runCatching { SystemCallController.enrich(applicationContext, saved, requestedAtMs) }
                .onFailure { Log.w(TAG, "无法启动通话记录补全", it) }
        }
    }

    private fun notifyForeground(text: String) {
        // 使用桌面图标的任务入口语义：已有任务回到前台，没有任务时启动根页面。
        val launch = Intent.makeMainActivity(ComponentName(this, MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val open = PendingIntent.getActivity(this, 0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "recording")
            .setSmallIcon(R.drawable.ic_recording).setContentTitle("Dial Record Labs")
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .build()
        ServiceCompat.startForeground(this, 1, notification,
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
    }

    private fun send(target: ResultReceiver?, result: RecordingResult) {
        runCatching { target?.send(RESULT_EVENT, result.toBundle()) }
            .onFailure { Log.w(TAG, "无法投递录音事件", it) }
    }

    override fun onDestroy() {
        // 正常结束时 session 已清空；异常销毁尽力封装正在录制的音频。
        finishSession(RecordingResult.Event.FAILED, "录音服务已停止")
        super.onDestroy()
    }
}
