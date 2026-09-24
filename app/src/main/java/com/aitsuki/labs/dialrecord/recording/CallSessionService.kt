package com.aitsuki.labs.dialrecord.recording

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.*
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.aitsuki.labs.dialrecord.R
import com.aitsuki.labs.dialrecord.calllog.CallLogMatcher
import com.aitsuki.labs.dialrecord.data.RecordingEntry
import com.aitsuki.labs.dialrecord.data.RecordingStore
import com.aitsuki.labs.dialrecord.ui.MainActivity
import java.io.File


class CallSessionService : Service() {
    companion object {
        private const val TAG = "CallSessionService"
        private const val NOTIFICATION_CHANNEL_ID = "recording"

        private const val ACTION_PREPARE_SESSION = "com.aitsuki.labs.dialrecord.PREPARE_SESSION"
        private const val ACTION_CANCEL_WAITING_SESSION =
            "com.aitsuki.labs.dialrecord.CANCEL_WAITING_SESSION"

        private const val EXTRA_COMPLETION_RECEIVER = "completionReceiver"
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_PREPARATION_RECEIVER = "preparationReceiver"
        private const val EXTRA_SESSION_TOKEN = "sessionToken"

        const val EXTRA_ERROR_MESSAGE = "errorMessage"
        const val RESULT_SESSION_PREPARATION_FAILED = 0
        const val RESULT_SESSION_READY = 1
        const val RESULT_SESSION_FINISHED = 2

        // UI 和服务共享同一个状态源，外部只能读取派生能力。
        private val session = CallSession()

        val canStartDial: Boolean
            get() = session.canStartDial

        val hasSession: Boolean
            get() = session.hasSession

        /** 建立前台服务和电话监听；调用方收到准备成功后再拨号。启动异常交由调用方处理。 */
        fun prepareSession(
            context: Context,
            number: String,
            sessionToken: String,
            preparationReceiver: ResultReceiver,
            completionReceiver: ResultReceiver,
        ) {
            ContextCompat.startForegroundService(
                context, Intent(context, CallSessionService::class.java)
                    .setAction(ACTION_PREPARE_SESSION)
                    .putExtra(EXTRA_NUMBER, number)
                    .putExtra(EXTRA_SESSION_TOKEN, sessionToken)
                    .putExtra(EXTRA_PREPARATION_RECEIVER, preparationReceiver)
                    .putExtra(EXTRA_COMPLETION_RECEIVER, completionReceiver)
            )
        }

        /** 仅取消 token 匹配且仍在等待拨号的会话，不中断通话或录音收尾。 */
        fun cancelWaitingSession(context: Context, sessionToken: String) {
            context.startService(
                Intent(context, CallSessionService::class.java)
                    .setAction(ACTION_CANCEL_WAITING_SESSION)
                    .putExtra(EXTRA_SESSION_TOKEN, sessionToken)
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var completionReceiver: ResultReceiver? = null
    private var recordingError: String? = null
    private var number: String? = null
    private var sessionToken: String? = null
    private var dialRequestedAtMs = 0L
    private var receiverRegistered = false
    private var recorder: MediaRecorder? = null
    private var recordingEntry: RecordingEntry? = null
    private var callLogMatcher: CallLogMatcher? = null
    private val offHookTimeout = Runnable {
        if (session.isWaitingForOffHook) {
            Log.w(TAG, "等待 OFFHOOK 超时，结束会话 number=$number, sessionToken=$sessionToken")
            endSessionAndStopService(CallSessionResult.Outcome.TIMED_OUT, error = "等待拨号超时")
        }
    }
    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!session.hasSession) return
            val state = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> CallSession.PhoneState.RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> CallSession.PhoneState.OFFHOOK
                TelephonyManager.EXTRA_STATE_IDLE -> CallSession.PhoneState.IDLE
                else -> return
            }
            Log.d(
                TAG,
                "电话状态：$state，会话状态：${session.state}, number=$number, sessionToken=$sessionToken"
            )
            val effect = traceSessionTransition("phoneState=$state") {
                session.onPhoneState(state)
            }
            when (effect) {
                CallSession.Effect.START_RECORDING -> {
                    handler.removeCallbacks(offHookTimeout)
                    startRecording()
                }

                CallSession.Effect.BEGIN_FINALIZATION -> finalizeSession()
                CallSession.Effect.NONE -> Unit
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "通话录音",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(
            TAG,
            "onStartCommand: intent=$intent, startId=$startId, number=$number, sessionToken=$sessionToken, state=${session.state}"
        )
        if (intent?.action == ACTION_CANCEL_WAITING_SESSION) {
            if (!session.hasSession || (session.isWaitingForOffHook && sessionToken == intent.getStringExtra(
                    EXTRA_SESSION_TOKEN
                ))
            ) {
                Log.d(
                    TAG,
                    "onStartCommand: 取消等待拨号的会话 number=$number, sessionToken=$sessionToken"
                )
                endSessionAndStopService(CallSessionResult.Outcome.CANCELLED)
            } else {
                Log.d(
                    TAG,
                    "onStartCommand: 忽略取消请求，state=${session.state}, sessionToken=$sessionToken, requestToken=${
                        intent.getStringExtra(EXTRA_SESSION_TOKEN)
                    }"
                )
            }
            return START_NOT_STICKY
        }
        val preparationReceiver = intent?.let {
            IntentCompat.getParcelableExtra(
                it,
                EXTRA_PREPARATION_RECEIVER,
                ResultReceiver::class.java
            )
        }
        val requestedReceiver = intent?.let {
            IntentCompat.getParcelableExtra(it, EXTRA_COMPLETION_RECEIVER, ResultReceiver::class.java)
        }
        fun rejectRequest(message: String?) {
            val token = intent?.getStringExtra(EXTRA_SESSION_TOKEN) ?: return
            sendCompletion(requestedReceiver, CallSessionResult(
                token, intent.getStringExtra(EXTRA_NUMBER).orEmpty(),
                CallSessionResult.Outcome.FAILED, errorMessage = message,
            ))
        }
        if (intent?.action != ACTION_PREPARE_SESSION || !session.canStartDial) {
            Log.w(
                TAG,
                "onStartCommand: 拒绝请求 action=${intent?.action}, number=$number, sessionToken=$sessionToken"
            )
            preparationReceiver?.send(RESULT_SESSION_PREPARATION_FAILED, Bundle().apply {
                putString(EXTRA_ERROR_MESSAGE, "正在通话或处理录音，请稍后再试")
            })
            rejectRequest("正在通话或处理录音，请稍后再试")
            if (!session.hasSession) stopSelf()
            return START_NOT_STICKY
        }
        val requestedNumber = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
        val requestedToken = intent.getStringExtra(EXTRA_SESSION_TOKEN)
        try {
            require(Regex("\\+?[0-9]{1,32}").matches(requestedNumber)) { "电话号码无效" }
            requireNotNull(requestedToken)
            // OFFHOOK 广播可能尚未送达；此时不能替换已经开始通话的会话。
            @SuppressLint("MissingPermission")
            check(!getSystemService(TelecomManager::class.java).isInCall) { "当前已有通话" }
        } catch (e: Exception) {
            Log.w(TAG, "拒绝拨号请求，保留现有会话", e)
            preparationReceiver?.send(RESULT_SESSION_PREPARATION_FAILED, Bundle().apply {
                putString(EXTRA_ERROR_MESSAGE, e.message)
            })
            rejectRequest(e.message)
            if (!session.hasSession) stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (session.isWaitingForOffHook) {
                Log.d(TAG, "替换尚未开始通话的会话 sessionToken=$sessionToken")
                finishSession(CallSessionResult.Outcome.CANCELLED, error = "被新的拨号请求替换")
            }
            completionReceiver = requestedReceiver
            number = requestedNumber
            sessionToken = requestedToken
            updateForegroundNotification("等待本 App 发起的通话")
            traceSessionTransition("新拨号请求") { session.begin() }
            dialRequestedAtMs = System.currentTimeMillis()
            ContextCompat.registerReceiver(
                this,
                phoneStateReceiver,
                IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
            handler.postDelayed(offHookTimeout, 90_000)
            // 先建立前台服务和电话监听，再允许 Activity 发起 ACTION_CALL。
            Log.d(
                TAG,
                "onStartCommand: 服务准备完成 number=$number, sessionToken=$sessionToken, dialRequestedAtMs=$dialRequestedAtMs"
            )
            preparationReceiver?.send(RESULT_SESSION_READY, Bundle.EMPTY)
        } catch (e: Exception) {
            Log.e(TAG, "无法准备拨号会话", e)
            preparationReceiver?.send(RESULT_SESSION_PREPARATION_FAILED, Bundle().apply {
                putString(EXTRA_ERROR_MESSAGE, e.message)
            })
            endSessionAndStopService(CallSessionResult.Outcome.FAILED, error = e.message)
        }
        return START_NOT_STICKY
    }

    private fun updateForegroundNotification(text: String) {
        Log.d(TAG, "updateForegroundNotification: $text")
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val open = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_recording)
            .setContentTitle("Dial Record Labs").setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
        ServiceCompat.startForeground(
            this, 1, notification,
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        )
    }

    private fun startRecording() {
        val entry = RecordingEntry(number!!, System.currentTimeMillis())
        Log.d(TAG, "startRecording: sessionToken=$sessionToken, file=${entry.fileName}")
        try {
            @Suppress("DEPRECATION")
            val media = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            recorder = media
            recordingEntry = entry
            media.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            media.setAudioEncodingBitRate(128_000)
            media.setAudioSamplingRate(44_100)
            media.setOutputFile(File(RecordingStore.directory(this), entry.fileName).absolutePath)
            media.prepare()
            media.start()
            RecordingStore.saveMetadata(this, entry)
            Log.d(TAG, "startRecording: 录音已启动 file=${entry.fileName}")
            updateForegroundNotification("通话录音中，挂断后自动保存")
        } catch (e: Exception) {
            recordingError = "无法启动录音：${e.message}"
            Log.e(TAG, "无法启动录音", e)
            recorder?.release()
            recorder = null
            recordingEntry = null
            deleteRecording(entry)
            Toast.makeText(this, "设备无法启动通话录音：${e.message}", Toast.LENGTH_LONG).show()
            updateForegroundNotification("录音失败，等待通话结束")
        }
    }

    private fun stopRecording(): RecordingEntry? {
        val media = recorder ?: return null
        val entry = requireNotNull(recordingEntry)
        Log.d(TAG, "stopRecording: sessionToken=$sessionToken, file=${entry.fileName}")
        recorder = null
        recordingEntry = null
        val saved = try {
            media.stop()
            true
        } catch (e: RuntimeException) {
            recordingError = "录音未生成有效音频：${e.message}"
            Log.w(TAG, "录音未生成有效音频", e)
            false
        } finally {
            media.release()
        }
        Log.d(TAG, "stopRecording: saved=$saved, file=${entry.fileName}")
        if (saved) return entry
        deleteRecording(entry)
        return null
    }

    private fun finalizeSession() {
        Log.d(TAG, "finalizeSession: number=$number, sessionToken=$sessionToken")
        try {
            val entry = stopRecording()
            if (entry == null) {
                Log.d(TAG, "finalizeSession: 没有有效录音，跳过关联 sessionToken=$sessionToken")
                endSessionAndStopService(
                    if (recordingError != null) CallSessionResult.Outcome.FAILED
                    else CallSessionResult.Outcome.CANCELLED,
                    error = recordingError,
                )
                return
            }
            updateForegroundNotification("录音已保存，正在关联系统通话记录")
            callLogMatcher = CallLogMatcher(this, entry, dialRequestedAtMs) { callLog ->
                Log.d(TAG, "finalizeSession: 关联完成 file=${entry.fileName}, callLog=$callLog")
                var finalEntry = entry
                var finalError: String? = null
                if (callLog != null) {
                    try {
                        val matched = entry.copy(callLog = callLog)
                        val directory = RecordingStore.directory(this)
                        val source = File(directory, entry.fileName)
                        val target = File(directory, matched.fileName)
                        check(!target.exists() && source.renameTo(target)) { "录音文件重命名失败" }
                        finalEntry = matched
                        RecordingStore.saveMetadata(this, matched)
                        Log.d(
                            TAG,
                            "finalizeSession: 重命名成功 ${source.name} -> ${target.name}, callId=${callLog.id}"
                        )
                    } catch (e: Exception) {
                        finalError = "无法保存通话关联结果：${e.message}"
                        Log.e(TAG, "无法保存通话关联结果", e)
                    }
                }
                endSessionAndStopService(
                    if (finalError == null) CallSessionResult.Outcome.COMPLETED
                    else CallSessionResult.Outcome.FAILED,
                    finalEntry, finalError,
                )
            }
            callLogMatcher!!.start()
        } catch (e: Exception) {
            Log.e(TAG, "无法关联本次通话记录", e)
            endSessionAndStopService(CallSessionResult.Outcome.FAILED, error = e.message)
        }
    }

    private fun deleteRecording(entry: RecordingEntry) {
        Log.d(TAG, "deleteRecording: 清理失败的录音 file=${entry.fileName}")
        try {
            File(RecordingStore.directory(this), entry.fileName).delete()
            RecordingStore.removeMetadata(this, entry.recordingStartedAtMs)
        } catch (e: Exception) {
            Log.e(TAG, "无法清理失败的录音", e)
        }
    }

    /** 释放会话资源并停止服务，用于完成、取消、超时或失败。 */
    private fun endSessionAndStopService(
        outcome: CallSessionResult.Outcome,
        entry: RecordingEntry? = null,
        error: String? = null,
    ) {
        finishSession(outcome, entry, error)
        stopSelf()
    }

    private fun finishSession(
        outcome: CallSessionResult.Outcome,
        entry: RecordingEntry? = null,
        error: String? = null,
    ) {
        val receiver = completionReceiver
        val result = sessionToken?.let {
            CallSessionResult(it, number.orEmpty(), outcome, entry, error)
        }
        // 清理前取快照，清理后投递，确保回调时服务已恢复空闲。
        completionReceiver = null
        releaseSessionResources()
        if (result != null) sendCompletion(receiver, result)
    }

    private fun sendCompletion(receiver: ResultReceiver?, result: CallSessionResult) {
        runCatching { receiver?.send(RESULT_SESSION_FINISHED, result.toBundle()) }
            .onFailure { Log.w(TAG, "无法投递会话结果 sessionToken=${result.sessionToken}", it) }
    }

    /** 仅释放资源并恢复空闲；替换等待请求时仍复用当前服务。 */
    private fun releaseSessionResources() {
        Log.d(
            TAG,
            "releaseSessionResources: sessionToken=$sessionToken, state=${session.state}, receiverRegistered=$receiverRegistered, recordingEntry=${recordingEntry?.fileName}"
        )
        traceSessionTransition("开始清理资源") { session.beginFinalizing() }
        handler.removeCallbacks(offHookTimeout)
        number = null
        callLogMatcher?.close()
        callLogMatcher = null

        if (receiverRegistered) {
            receiverRegistered = false
            try {
                unregisterReceiver(phoneStateReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "无法注销电话状态监听", e)
            }
        }
        try {
            stopRecording()
        } catch (e: Exception) {
            Log.e(TAG, "结束录音失败", e)
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.e(TAG, "无法移除前台通知", e)
        }
        traceSessionTransition("资源清理完成") { session.reset() }
        sessionToken = null
        recordingError = null
    }

    private inline fun <T> traceSessionTransition(reason: String, action: () -> T): T {
        val previous = session.state
        return try {
            action()
        } finally {
            Log.d(
                TAG,
                "sessionState: $previous -> ${session.state}, reason=$reason, sessionToken=$sessionToken, canStartDial=${session.canStartDial}, hasSession=${session.hasSession}"
            )
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: number=$number, sessionToken=$sessionToken")
        finishSession(CallSessionResult.Outcome.FAILED, error = "录音服务已停止")
        super.onDestroy()
    }
}
