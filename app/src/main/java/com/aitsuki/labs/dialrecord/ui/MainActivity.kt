package com.aitsuki.labs.dialrecord.ui

import android.Manifest.permission.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.aitsuki.labs.dialrecord.LabApplication
import com.aitsuki.labs.dialrecord.accessibility.CallAccessibilityService
import com.aitsuki.labs.dialrecord.calls.CallLogMatcher
import com.aitsuki.labs.dialrecord.calls.CallLogWindow
import com.aitsuki.labs.dialrecord.calls.SystemCallController
import com.aitsuki.labs.dialrecord.databinding.ActivityMainBinding
import com.aitsuki.labs.dialrecord.recording.RecordingFiles
import com.aitsuki.labs.dialrecord.recording.RecordingService
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 原生页面模拟 H5 请求；不引入 WebView，也不恢复已销毁页面的 callId。 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private enum class CallMode(val recordingEnabled: Boolean) {
        SYSTEM_RECORD(true), SYSTEM(false), SDK_SIMULATION(
            true
        )
    }

    private data class CallRequest(
        val callId: String,
        val phoneNumber: String,
        val callMode: CallMode,
        val userId: String
    )

    private val startupPermissions = buildList {
        addAll(listOf(CALL_PHONE, READ_PHONE_STATE, READ_CALL_LOG, RECORD_AUDIO))
        if (Build.VERSION.SDK_INT >= 33) add(POST_NOTIFICATIONS)
    }
    private var startupPermissionsGranted = false
    private var permissionRequestInFlight = false
    private var activeCallJob: Job? = null
    private var sdkCallDurationResult: CompletableDeferred<Long?>? = null

    private val startupPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionRequestInFlight = false
            startupPermissionsGranted = startupPermissions.all(::hasPermission)
            if (startupPermissionsGranted) setCallControlsBusy(false)
            else exitForMissingPermissions()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val contentPaddingPx = (24 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { rootView, windowInsets ->
            val systemBarAndImeInsets =
                windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            rootView.setPadding(
                contentPaddingPx + systemBarAndImeInsets.left,
                contentPaddingPx + systemBarAndImeInsets.top,
                contentPaddingPx + systemBarAndImeInsets.right,
                contentPaddingPx + systemBarAndImeInsets.bottom
            )
            windowInsets
        }
        binding.source.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(
                "System call · Record",
                "System call · No recording",
                "SDK event simulation · Manual recording"
            )
        )
        binding.dialButton.setOnClickListener { requestCall() }
        binding.stopButton.setOnClickListener {
            val sdkDurationInput = binding.sdkDuration.text.toString().trim()
            val sdkCallDurationSeconds = sdkDurationInput.toLongOrNull()
            if (sdkDurationInput.isNotEmpty() && (sdkCallDurationSeconds == null || sdkCallDurationSeconds < 0)) {
                binding.sdkDuration.error =
                    "Enter a non-negative integer, or leave blank for unknown"
            } else sdkCallDurationResult?.complete(sdkCallDurationSeconds)
        }
        permissionRequestInFlight =
            savedInstanceState?.getBoolean("permissionRequestInFlight") ?: false
        requestStartupPermissions()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("permissionRequestInFlight", permissionRequestInFlight)
        super.onSaveInstanceState(outState)
    }

    /** 启动时统一授权；授权完成前禁用操作，任一权限被拒绝则退出页面。 */
    private fun requestStartupPermissions() {
        val missingPermissions = startupPermissions.filterNot(::hasPermission)
        startupPermissionsGranted = missingPermissions.isEmpty()
        setCallControlsBusy(!startupPermissionsGranted)
        if (startupPermissionsGranted || permissionRequestInFlight) return

        permissionRequestInFlight = true
        runCatching { startupPermissionLauncher.launch(missingPermissions.toTypedArray()) }
            .onFailure {
                permissionRequestInFlight = false
                exitForMissingPermissions()
            }
    }

    private fun exitForMissingPermissions() {
        startupPermissionsGranted = false
        setCallControlsBusy(true)
        Toast.makeText(
            this,
            "All requested permissions are required. Closing the app.",
            Toast.LENGTH_LONG
        ).show()
        finishAndRemoveTask()
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requestCall() {
        if (!startupPermissionsGranted || activeCallJob?.isActive == true) return
        // 号码是业务关联键：保留用户原始输入，不裁剪、不格式化；无效输入直接拒绝。
        val phoneNumber = binding.phoneNumber.text.toString()
        if (!Regex("\\+?[0-9]{1,32}").matches(phoneNumber)) {
            binding.phoneNumber.error = "Enter a valid phone number"
            return
        }
        val selectedCallMode = CallMode.entries[binding.source.selectedItemPosition]
        val userIdInput = binding.userId.text.toString().trim()
        if (selectedCallMode.recordingEnabled && !RecordingFiles.validUserId(userIdInput)) {
            binding.userId.error = "Enter a correlation userId (letters, digits, or hyphens)"
            return
        }
        val callRequest = CallRequest(
            callId = UUID.randomUUID().toString(),
            phoneNumber = phoneNumber,
            callMode = selectedCallMode,
            userId = userIdInput
        )
        // 保留现有系统录音实验的无障碍前置条件；不录音和 SDK 模拟不需要。
        if (callRequest.callMode == CallMode.SYSTEM_RECORD && !CallAccessibilityService.isConnected) {
            Toast.makeText(
                this,
                "Enable the accessibility service, then try again",
                Toast.LENGTH_LONG
            ).show()
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .onFailure {
                    completeCallRequest(
                        callRequest,
                        JSONObject().put("error", "Unable to open accessibility settings")
                    )
                }
            return
        }
        setCallControlsBusy(true)
        binding.result.text = ""
        binding.recordingStatus.text =
            if (selectedCallMode.recordingEnabled) "Waiting to record" else "No recording for this request"
        launchCallRequest(callRequest)
    }

    private fun launchCallRequest(callRequest: CallRequest) {
        activeCallJob = lifecycleScope.launch {
            try {
                // 等页面恢复到前台后再启动录音服务。
                awaitActivityResumed()
                val callResultJson = if (callRequest.callMode.recordingEnabled) {
                    RecordingService.withRecorder(this@MainActivity) { recordingService ->
                        executeCallRequest(callRequest, recordingService)
                    }
                } else executeCallRequest(callRequest, null)
                completeCallRequest(callRequest, callResultJson)
            } catch (e: TimeoutCancellationException) {
                completeCallRequest(
                    callRequest,
                    JSONObject().put("error", "Timed out preparing the recording service")
                )
            } catch (e: CancellationException) {
                throw e // 页面销毁：清理即可，不向新页面重放结果。
            } catch (e: Exception) {
                completeCallRequest(
                    callRequest,
                    JSONObject().put("error", e.message ?: "Request failed")
                )
            } finally {
                sdkCallDurationResult = null
                setCallControlsBusy(false)
            }
        }
    }

    private suspend fun awaitActivityResumed() {
        val activityResumedSignal = CompletableDeferred<Unit>()
        val resumeObserver = LifecycleEventObserver { _, lifecycleEvent ->
            if (lifecycleEvent == Lifecycle.Event.ON_RESUME) activityResumedSignal.complete(Unit)
        }
        lifecycle.addObserver(resumeObserver) // 已处于 RESUMED 时也会立即收到对应事件。
        try {
            activityResumedSignal.await()
        } finally {
            lifecycle.removeObserver(resumeObserver)
        }
    }

    private suspend fun executeCallRequest(
        callRequest: CallRequest,
        recordingService: RecordingService?
    ): JSONObject {
        val callResultJson = JSONObject()
        // 回调与录音文件始终使用请求号码，系统通话记录不得覆盖业务关联键。
        val correlationPhoneNumber = callRequest.phoneNumber
        var correlationDateMs: Long? = null
        var callDurationSeconds: Long? = null
        try {
            if (callRequest.callMode == CallMode.SDK_SIMULATION) {
                val sdkDurationResult = CompletableDeferred<Long?>()
                sdkCallDurationResult = sdkDurationResult
                val sdkCallStartedAtMs = System.currentTimeMillis()
                recordingService?.start()
                binding.stopButton.isEnabled = true
                binding.sessionStatus.text =
                    "Recording simulated SDK call; enter the SDK call duration when ending manually (may be unknown)"
                callDurationSeconds = sdkDurationResult.await()
                correlationDateMs =
                    (recordingService?.finish()?.startedAtMs ?: sdkCallStartedAtMs) / 1_000 * 1_000
                callResultJson.put(
                    "sdk", JSONObject().put("simulated", true).put("number", correlationPhoneNumber)
                        .put("date", correlationDateMs)
                        .put("duration", callDurationSeconds ?: JSONObject.NULL)
                )
            } else {
                val preDialCallLogId = CallLogMatcher.latestId(this)
                // 查询水位期间用户可能已经离开页面，此时不再自动拨号。
                check(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { "The Activity is no longer active; please start the call again" }
                binding.sessionStatus.text = "Waiting for the system call to start"
                val dialingWindow = SystemCallController.call(this, callRequest.phoneNumber) {
                    binding.sessionStatus.text =
                        "System call in progress (OFFHOOK does not imply an answer)"
                    recordingService?.start()
                }
                val recordingResult = recordingService?.finish() // 挂断立即封装，查询记录不延长录音。
                binding.sessionStatus.text = "Call ended; waiting for its system call log entry"
                val matchedCallLog = CallLogMatcher.await(
                    this, callRequest.phoneNumber,
                    CallLogWindow(
                        preDialCallLogId,
                        dialingWindow.requestedAtMs - 2_000,
                        dialingWindow.offhookAtMs + 2_000
                    )
                )
                if (matchedCallLog == null) {
                    callResultJson.put("callLog", JSONObject.NULL)
                        .put("error", "No unique matching call log entry was found for this call")
                } else {
                    // 沿用线上关联约定：有录音时以录音开始时间为 date，精确到秒。
                    correlationDateMs =
                        (recordingResult?.startedAtMs ?: matchedCallLog.date) / 1_000 * 1_000
                    callDurationSeconds = matchedCallLog.duration
                    callResultJson.put(
                        "callLog", JSONObject()
                            .put("id", matchedCallLog.id).put("number", correlationPhoneNumber)
                            .put("systemNumber", matchedCallLog.number)
                            .put("date", correlationDateMs)
                            .put("systemDate", matchedCallLog.date)
                            .put("duration", callDurationSeconds).put("type", matchedCallLog.type)
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            callResultJson.put("error", "Timed out waiting for system dialing")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            callResultJson.put("error", e.message ?: "Call failed")
        } finally {
            recordingService?.finish()?.let { recordingResult ->
                // Android 本地诊断，与 H5 回调 JSON 分开，绝不把文件地址交给 H5 上传。
                binding.recordingStatus.text =
                    recordingResult.error ?: if (recordingResult.file != null) {
                        "Finalized; retained in staging until correlation information is complete: ${recordingResult.file.name}"
                    } else "No recording was produced for this request"
            }
        }
        val finalizedRecordingFile = recordingService?.finish()?.file
        if (finalizedRecordingFile != null && correlationDateMs != null && callDurationSeconds != null) {
            try {
                val pendingUploadFile = withContext(Dispatchers.IO) {
                    (application as LabApplication).recordingFiles.publish(
                        finalizedRecordingFile,
                        callRequest.userId,
                        correlationPhoneNumber,
                        correlationDateMs,
                        callDurationSeconds
                    )
                }
                binding.recordingStatus.text =
                    "Added to the pending upload directory: ${pendingUploadFile.name} (lab upload API not yet integrated)"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.recordingStatus.text =
                    "Unable to publish recording; original file retained: ${e.message}"
            }
        }
        return callResultJson
    }

    /** 这里就是移植到线上时调用 H5 的位置；callId 始终来自同一个不可变请求。 */
    private fun completeCallRequest(callRequest: CallRequest, callResultJson: JSONObject) {
        callResultJson.put("callId", callRequest.callId).put("number", callRequest.phoneNumber)
            .put("mode", callRequest.callMode.name)
        binding.result.text = callResultJson.toString(2)
        binding.sessionStatus.text = "Request completed"
        Log.i("CallResult", callResultJson.toString())
    }

    private fun setCallControlsBusy(isCallRequestActive: Boolean) {
        binding.dialButton.isEnabled = !isCallRequestActive
        binding.source.isEnabled = !isCallRequestActive
        binding.phoneNumber.isEnabled = !isCallRequestActive
        binding.userId.isEnabled = !isCallRequestActive
        binding.stopButton.isEnabled = isCallRequestActive && sdkCallDurationResult != null
    }
}
