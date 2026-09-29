package com.aitsuki.labs.dialrecord.ui

import android.Manifest.permission.READ_PHONE_STATE
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.util.Log
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.aitsuki.labs.dialrecord.LabApplication
import com.aitsuki.labs.dialrecord.calls.CallLogMatcher
import com.aitsuki.labs.dialrecord.calls.CallLogWindow
import com.aitsuki.labs.dialrecord.calls.SystemCallController
import com.aitsuki.labs.dialrecord.recording.RecordingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal enum class CallMode(val recordingEnabled: Boolean) {
    SYSTEM_RECORD(true), SYSTEM(false), SDK_SIMULATION(true)
}

internal data class CallRequest(
    val callId: String,
    val phoneNumber: String,
    val callMode: CallMode,
    val userId: String
)

/**
 * 页面内的单请求控制器，所有入口和状态更新都在主线程执行。
 *
 * current 是唯一的请求归属：替换后旧请求只能清理自己的资源，不能再更新页面。
 * 新请求的协程先等待旧请求退出，再获取录音和电话监听资源；不存在两个资源持有者。
 * 页面销毁由 lifecycleScope 取消，不恢复请求，也不挂断系统电话。
 */
@MainThread
internal class CallRequestController(
    private val activity: AppCompatActivity,
    private val render: (State) -> Unit,
    private val notifyUser: (String) -> Unit
) {
    enum class Phase { IDLE, PREPARING, WAITING_FOR_OFFHOOK, IN_CALL, FINISHING }

    data class State(
        val phase: Phase = Phase.IDLE,
        val mode: CallMode? = null,
        val sessionStatus: String = "Ready",
        val recordingStatus: String = "",
        val result: String = ""
    ) {
        val canDial get() = phase == Phase.IDLE || phase == Phase.WAITING_FOR_OFFHOOK
        val canEndSimulation get() = phase == Phase.IN_CALL && mode == CallMode.SDK_SIMULATION
    }

    private data class CallOutcome(
        val json: JSONObject,
        val dateMs: Long? = null,
        val durationSeconds: Long? = null
    )

    private var current: Session? = null
    val state: State get() = current?.state ?: State()

    fun submit(request: CallRequest) {
        if (!state.canDial) return
        // 拒绝重试时不触碰原请求，原来的通话监听必须保留。
        try {
            requireForeground()
            check(!isSystemInCall()) { "A call is already in progress" }
        } catch (e: Exception) {
            notifyUser(e.message ?: "Unable to start the request")
            return
        }

        val next = Session(request, previous = current)
        // 先建立完整的请求归属再执行协程，避免同步执行/结束时尚未保存 job。
        current = next
        render(next.state) // PREPARING 同时覆盖资源准备与交接，禁止连点。
        next.job.start()
    }

    fun endSimulation(durationSeconds: Long?) {
        require(durationSeconds == null || durationSeconds >= 0)
        val session = current ?: return
        if (session.state.canEndSimulation) session.sdkDuration.complete(durationSeconds)
    }

    private fun isSystemInCall(): Boolean {
        if (ContextCompat.checkSelfPermission(activity, READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Phone state permission is required")
        }
        return activity.getSystemService(TelecomManager::class.java).isInCall
    }

    private fun requireForeground() {
        check(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            "The Activity is no longer active; please start the call again"
        }
    }

    private inner class Session(val request: CallRequest, previous: Session?) {
        val job: Job = activity.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            runAfter(previous)
        }
        val sdkDuration = CompletableDeferred<Long?>()
        var state = State(
            phase = Phase.PREPARING,
            mode = request.callMode,
            sessionStatus = "Preparing request",
            recordingStatus = if (request.callMode.recordingEnabled) "Waiting to record" else "No recording for this request"
        )
            private set

        private fun update(change: State.() -> State) {
            if (current !== this || activity.lifecycle.currentState == Lifecycle.State.DESTROYED) return
            state = state.change()
            render(state)
        }

        private suspend fun runAfter(previous: Session?) {
            try {
                // 唯一交接顺序：旧请求退出（包括 finally）→ 复查 → 获取新资源。
                previous?.job?.cancelAndJoin()
                requireForeground()
                check(!isSystemInCall()) { "A call is already in progress" }
                val result = if (request.callMode.recordingEnabled) {
                    RecordingService.withRecorder(activity) { recorder -> execute(recorder) }
                } else execute(null)
                complete(result)
            } catch (e: TimeoutCancellationException) {
                complete(JSONObject().put("error", "Timed out preparing the recording service"))
            } catch (e: CancellationException) {
                throw e // 被替换或页面销毁：只清理，不回调成拨号失败。
            } catch (e: Exception) {
                complete(JSONObject().put("error", e.message ?: "Request failed"))
            } finally {
                update { copy(phase = Phase.IDLE) }
            }
        }

        private suspend fun execute(recorder: RecordingService?): JSONObject {
            val outcome = try {
                when (request.callMode) {
                    CallMode.SDK_SIMULATION -> awaitSimulation(recorder)
                    CallMode.SYSTEM, CallMode.SYSTEM_RECORD -> awaitSystemCall(recorder)
                }
            } catch (e: TimeoutCancellationException) {
                CallOutcome(JSONObject().put("error", "Timed out waiting for system dialing"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CallOutcome(JSONObject().put("error", e.message ?: "Call failed"))
            } finally {
                // 清理不受请求归属限制；只有展示受限制。finish 是幂等操作。
                recorder?.finish()?.let { recording ->
                    val message = recording.error ?: if (recording.file != null) {
                        "Finalized; retained in staging until correlation information is complete: ${recording.file.name}"
                    } else "No recording was produced for this request"
                    update { copy(recordingStatus = message) }
                }
            }
            publishRecording(recorder, outcome)
            return outcome.json
        }

        private suspend fun awaitSimulation(recorder: RecordingService?): CallOutcome {
            val startedAtMs = System.currentTimeMillis()
            recorder?.start()
            update {
                copy(
                    phase = Phase.IN_CALL,
                    sessionStatus = "Recording simulated SDK call; enter the SDK call duration when ending manually (may be unknown)"
                )
            }
            val duration = sdkDuration.await()
            update { copy(phase = Phase.FINISHING) }
            val date = (recorder?.finish()?.startedAtMs ?: startedAtMs) / 1_000 * 1_000
            val json = JSONObject().put("sdk", JSONObject()
                .put("simulated", true).put("number", request.phoneNumber)
                .put("date", date).put("duration", duration ?: JSONObject.NULL))
            return CallOutcome(json, date, duration)
        }

        private suspend fun awaitSystemCall(recorder: RecordingService?): CallOutcome {
            val preDialCallLogId = CallLogMatcher.latestId(activity)
            requireForeground() // 查询水位时可能已经离开页面，此时不自动拨号。
            update {
                copy(
                    phase = Phase.WAITING_FOR_OFFHOOK,
                    sessionStatus = "Waiting for the system call to start; you can retry dialing"
                )
            }
            val window = SystemCallController.call(activity, request.phoneNumber) {
                update {
                    copy(
                        phase = Phase.IN_CALL,
                        sessionStatus = "System call in progress (OFFHOOK does not imply an answer)"
                    )
                }
                recorder?.start()
            }
            val recording = recorder?.finish() // 挂断立即结束录音，查询记录不延长录音。
            update {
                copy(
                    phase = Phase.FINISHING,
                    sessionStatus = "Call ended; waiting for its system call log entry"
                )
            }
            val callLog = CallLogMatcher.await(
                activity, request.phoneNumber,
                CallLogWindow(preDialCallLogId, window.requestedAtMs - 2_000, window.offhookAtMs + 2_000)
            ) ?: return CallOutcome(JSONObject()
                .put("callLog", JSONObject.NULL)
                .put("error", "No unique matching call log entry was found for this call"))

            // 请求号码始终是业务关联键，不被系统记录中的号码覆盖。
            val date = (recording?.startedAtMs ?: callLog.date) / 1_000 * 1_000
            val json = JSONObject().put("callLog", JSONObject()
                .put("id", callLog.id).put("number", request.phoneNumber)
                .put("systemNumber", callLog.number).put("date", date)
                .put("systemDate", callLog.date).put("duration", callLog.duration).put("type", callLog.type))
            return CallOutcome(json, date, callLog.duration)
        }

        private suspend fun publishRecording(recorder: RecordingService?, outcome: CallOutcome) {
            val file = recorder?.finish()?.file ?: return
            val date = outcome.dateMs ?: return
            val duration = outcome.durationSeconds ?: return
            try {
                val pendingFile = withContext(Dispatchers.IO) {
                    (activity.application as LabApplication).recordingFiles.publish(
                        file, request.userId, request.phoneNumber, date, duration
                    )
                }
                val message = if (pendingFile == null) {
                    "Recording deleted: call duration is less than 5 seconds; upload skipped"
                } else {
                    "Added to the pending upload directory: ${pendingFile.name} (lab upload API not yet integrated)"
                }
                update { copy(recordingStatus = message) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { copy(recordingStatus = "Unable to publish recording; original file retained: ${e.message}") }
            }
        }

        /** 移植到线上时在此回调 H5；旧请求不得向当前页面补发结果。 */
        private fun complete(result: JSONObject) {
            if (current !== this || activity.lifecycle.currentState == Lifecycle.State.DESTROYED) return
            result.put("callId", request.callId).put("number", request.phoneNumber).put("mode", request.callMode.name)
            Log.i("CallResult", result.toString())
            update { copy(sessionStatus = "Request completed", result = result.toString(2)) }
        }
    }
}
