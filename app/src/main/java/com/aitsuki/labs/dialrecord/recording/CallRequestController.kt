package com.aitsuki.labs.dialrecord.recording

import android.Manifest.permission.READ_PHONE_STATE
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.aitsuki.labs.dialrecord.AppLog
import com.aitsuki.labs.dialrecord.LabApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class CallRequest(
    val callId: String,
    val phoneNumber: String,
    val needRecord: Boolean,
    val userId: String
)

/**
 * 协调页面内的系统通话请求
 *
 * 仅等待 OFFHOOK 时允许替换请求；新请求先等待旧请求完整退出，再获取资源。
 * current 是唯一请求归属，旧请求可以清理资源，但不能回调或清除新请求。
 * 所有入口在主线程执行，页面销毁由 lifecycleScope 取消，不挂断系统电话。
 */
@MainThread
internal class CallRequestController(
    private val activity: AppCompatActivity,
    private val onResult: (CallRequest, JSONObject) -> Unit
) {
    private enum class Phase { PREPARING, WAITING_FOR_OFFHOOK, IN_CALL, FINISHING }

    private var current: Session? = null

    /** 供入口拒绝日志使用，不向外暴露可变会话或阶段。 */
    val activeRequestDescription: String
        get() = "activeCallId=${current?.request?.callId}, phase=${current?.phase}"
    val canSubmit: Boolean get() = current == null || current?.phase == Phase.WAITING_FOR_OFFHOOK

    /** 前置检查失败时保留原请求，由入口处理异常。 */
    fun submit(request: CallRequest) {
        if (!canSubmit) {
            AppLog.warn(
                "拒绝系统通话请求：当前阶段不允许重试，callId=%s，activeCallId=%s，phase=%s",
                request.callId, current?.request?.callId, current?.phase
            )
            return
        }
        try {
            requireCallReady()
        } catch (e: Exception) {
            AppLog.warn(
                "拒绝系统通话请求：前置检查失败，callId=%s，activeCallId=%s，phase=%s，原因=%s",
                request.callId, current?.request?.callId, current?.phase, e.message
            )
            throw e // 保留原请求，仍由入口处理异常。
        }
        val next = Session(request, previous = current)
        current = next // 先建立归属及 PREPARING 状态，防止交接期间连点。
        next.job.start()
    }

    private fun requireForeground() {
        check(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            "The Activity is no longer active; please start the call again"
        }
    }

    private fun requireCallReady() {
        requireForeground()
        check(
            ContextCompat.checkSelfPermission(
                activity,
                READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            "Phone state permission is required"
        }
        check(!activity.getSystemService(TelecomManager::class.java).isInCall) {
            "A call is already in progress"
        }
    }

    private inner class Session(val request: CallRequest, previous: Session?) {
        var phase = Phase.PREPARING
            private set
        val job: Job = activity.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            runAfter(previous)
        }

        private suspend fun runAfter(previous: Session?) {
            AppLog.info(
                "收到系统通话请求：callId=%s，号码=%s，需要录音=%s",
                request.callId,
                request.phoneNumber,
                request.needRecord
            )
            try {
                previous?.job?.cancelAndJoin()
                requireCallReady()
                val result = if (request.needRecord) {
                    RecordingService.withRecorder(activity) { execute(it) }
                } else {
                    execute(null)
                }
                if (result != null && current === this &&
                    activity.lifecycle.currentState != Lifecycle.State.DESTROYED
                ) {
                    onResult(request, result)
                }
            } catch (_: TimeoutCancellationException) {
                AppLog.warn("系统通话请求等待超时：callId=%s", request.callId)
            } catch (e: CancellationException) {
                AppLog.info("系统通话请求已取消：callId=%s", request.callId)
                throw e // 被替换或页面销毁：只清理，不回调旧请求。
            } catch (e: Exception) {
                AppLog.error("系统通话请求失败：callId=%s，原因=%s", e, request.callId, e.message)
            } finally {
                if (current === this) current = null
            }
        }

        private suspend fun execute(recorder: RecordingService?): JSONObject? {
            var result: JSONObject? = null
            var dateMs: Long? = null
            var durationSeconds: Long? = null
            try {
                val preDialCallLogId = CallLogMatcher.latestId(activity)
                requireForeground() // 查询期间离开页面时不再自动拨号。
                phase = Phase.WAITING_FOR_OFFHOOK
                val window = SystemDialer.call(activity, request.phoneNumber) {
                    phase = Phase.IN_CALL
                    recorder?.start()
                }
                phase = Phase.FINISHING
                val recording = recorder?.finish() // 挂断立即封装，查询记录不延长录音。
                val callLog = CallLogMatcher.await(
                    activity,
                    number = request.phoneNumber,
                    window = CallLogWindow(
                        afterId = preDialCallLogId,
                        fromMs = window.requestedAtMs - 2_000,
                        toMs = window.offhookAtMs + 2_000
                    )
                )
                if (callLog != null) {
                    // 保留线上协议：有录音时以录音开始时间为 date，号码取请求号码。
                    dateMs = recording?.startedAtMs ?: callLog.date
                    durationSeconds = callLog.duration
                    result = JSONObject()
                        .put("id", callLog.id)
                        .put("number", request.phoneNumber)
                        .put("date", dateMs)
                        .put("type", callLog.type)
                        .put("duration", durationSeconds)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.error(
                    "系统通话执行失败：callId=%s，原因=%s",
                    e,
                    request.callId,
                    e.message
                )
            } finally {
                phase = Phase.FINISHING
            }
            publishRecording(recorder, dateMs, durationSeconds)
            if (result != null) {
                AppLog.info("系统通话结果已生成：callId=%s，result=%s", request.callId, result)
            } else {
                AppLog.warn("系统通话未生成结果：callId=%s", request.callId)
            }
            return result
        }

        private suspend fun publishRecording(
            recorder: RecordingService?,
            dateMs: Long?,
            durationSeconds: Long?
        ) {
            val file = recorder?.finish()?.file ?: return
            if (dateMs == null || durationSeconds == null) {
                AppLog.warn(
                    "系统通话录音缺少关联信息，保留在 staging，暂不上传：号码=%s，文件=%s",
                    request.phoneNumber, file.name
                )
                return
            }
            try {
                withContext(Dispatchers.IO) {
                    LabApplication.app.recordingFiles.publish(
                        audio = file,
                        userId = request.userId,
                        number = request.phoneNumber,
                        dateMs = dateMs,
                        duration = durationSeconds
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.error(
                    "系统通话录音加入待上传目录失败，保留原文件：号码=%s，文件=%s，原因=%s",
                    e, request.phoneNumber, file.name, e.message
                )
            }
        }
    }
}
