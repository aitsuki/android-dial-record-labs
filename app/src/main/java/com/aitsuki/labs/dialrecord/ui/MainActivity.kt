package com.aitsuki.labs.dialrecord.ui

import android.Manifest.permission.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.PhoneNumberUtils
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
    private enum class Mode(val recording: Boolean) { SYSTEM_RECORD(true), SYSTEM(false), SDK_SIMULATION(true) }
    private data class Request(val callId: String, val number: String, val mode: Mode, val userId: String)
    private var pendingRequest: Request? = null
    private var requestJob: Job? = null
    private var sdkEnded: CompletableDeferred<Long?>? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val request = pendingRequest ?: return@registerForActivityResult
        pendingRequest = null
        if (permissions(request).all(::hasPermission)) launch(request)
        else {
            complete(request, JSONObject().put("error", "缺少本次操作所需权限"))
            setBusy(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val padding = (24 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        binding.source.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("系统通话 · 录音", "系统通话 · 不录音", "SDK 事件模拟 · 手动录音"))
        binding.dialButton.setOnClickListener { requestCall() }
        binding.stopButton.setOnClickListener {
            val text = binding.sdkDuration.text.toString().trim()
            val duration = text.toLongOrNull()
            if (text.isNotEmpty() && (duration == null || duration < 0)) {
                binding.sdkDuration.error = "请输入非负整数，或留空表示未知"
            } else sdkEnded?.complete(duration)
        }
        setBusy(false)
    }

    private fun permissions(request: Request): List<String> = buildList {
        if (request.mode != Mode.SDK_SIMULATION) addAll(listOf(CALL_PHONE, READ_PHONE_STATE, READ_CALL_LOG))
        if (request.mode.recording) add(RECORD_AUDIO)
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requestCall() {
        if (pendingRequest != null || requestJob?.isActive == true) return
        val number = PhoneNumberUtils.normalizeNumber(binding.phoneNumber.text.toString().trim())
        if (!Regex("\\+?[0-9]{1,32}").matches(number)) {
            binding.phoneNumber.error = "请输入有效的电话号码"
            return
        }
        val mode = Mode.entries[binding.source.selectedItemPosition]
        val userId = binding.userId.text.toString().trim()
        if (mode.recording && !RecordingFiles.validUserId(userId)) {
            binding.userId.error = "请输入关联用 userId（字母、数字或连字符）"
            return
        }
        val request = Request(UUID.randomUUID().toString(), number, mode, userId)
        // 保留现有系统录音实验的无障碍前置条件；不录音和 SDK 模拟不需要。
        if (request.mode == Mode.SYSTEM_RECORD && !CallAccessibilityService.isConnected) {
            Toast.makeText(this, "请启用无障碍服务后重新发起", Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .onFailure { complete(request, JSONObject().put("error", "无法打开无障碍设置")) }
            return
        }
        setBusy(true)
        binding.result.text = ""
        binding.recordingStatus.text = if (mode.recording) "等待录音" else "本次不录音"
        val missing = permissions(request).filterNot(::hasPermission).toMutableList()
        if (request.mode.recording && Build.VERSION.SDK_INT >= 33 && !hasPermission(POST_NOTIFICATIONS)) {
            missing += POST_NOTIFICATIONS // 拒绝通知权限不阻止录音。
        }
        if (missing.isEmpty()) launch(request)
        else {
            pendingRequest = request
            runCatching { permissionLauncher.launch(missing.toTypedArray()) }.onFailure {
                pendingRequest = null
                complete(request, JSONObject().put("error", "无法申请权限：${it.message}"))
                setBusy(false)
            }
        }
    }

    private fun launch(request: Request) {
        requestJob = lifecycleScope.launch {
            try {
                // 权限结果可能在 onResume 之前交付，等页面恢复后再启动前台服务。
                awaitResumed()
                val result = if (request.mode.recording) {
                    RecordingService.withRecorder(this@MainActivity) { perform(request, it) }
                } else perform(request, null)
                complete(request, result)
            } catch (e: TimeoutCancellationException) {
                complete(request, JSONObject().put("error", "准备录音服务超时"))
            } catch (e: CancellationException) {
                throw e // 页面销毁：清理即可，不向新页面重放结果。
            } catch (e: Exception) {
                complete(request, JSONObject().put("error", e.message ?: "请求失败"))
            } finally {
                sdkEnded = null
                setBusy(false)
            }
        }
    }

    private suspend fun awaitResumed() {
        val resumed = CompletableDeferred<Unit>()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumed.complete(Unit)
        }
        lifecycle.addObserver(observer) // 已处于 RESUMED 时也会立即收到对应事件。
        try { resumed.await() } finally { lifecycle.removeObserver(observer) }
    }

    private suspend fun perform(request: Request, recorder: RecordingService?): JSONObject {
        val result = JSONObject()
        var number = request.number
        var dateMs: Long? = null
        var duration: Long? = null
        try {
            if (request.mode == Mode.SDK_SIMULATION) {
                val ended = CompletableDeferred<Long?>()
                sdkEnded = ended
                val startedAt = System.currentTimeMillis()
                recorder?.start()
                binding.stopButton.isEnabled = true
                binding.sessionStatus.text = "模拟 SDK 录音中；手动结束时传入 SDK 通话时长（可未知）"
                duration = ended.await()
                dateMs = (recorder?.finish()?.startedAtMs ?: startedAt) / 1_000 * 1_000
                result.put("sdk", JSONObject().put("simulated", true).put("number", number)
                    .put("date", dateMs).put("duration", duration ?: JSONObject.NULL))
            } else {
                val afterId = CallLogMatcher.latestId(this)
                // 查询水位期间用户可能已经离开页面，此时不再自动拨号。
                check(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { "页面已离开，请重新发起通话" }
                binding.sessionStatus.text = "等待系统通话开始"
                val window = SystemCallController.call(this, request.number) {
                    binding.sessionStatus.text = "系统通话中（OFFHOOK 不代表已接听）"
                    recorder?.start()
                }
                val recording = recorder?.finish() // 挂断立即封装，查询记录不延长录音。
                binding.sessionStatus.text = "通话已结束，等待本次系统记录"
                val call = CallLogMatcher.await(this, request.number,
                    CallLogWindow(afterId, window.requestedAtMs - 2_000, window.offhookAtMs + 2_000))
                if (call == null) {
                    result.put("callLog", JSONObject.NULL).put("error", "未找到唯一匹配的本次通话记录")
                } else {
                    number = PhoneNumberUtils.normalizeNumber(call.number)
                    // 沿用线上关联约定：有录音时以录音开始时间为 date，精确到秒。
                    dateMs = (recording?.startedAtMs ?: call.date) / 1_000 * 1_000
                    duration = call.duration
                    result.put("callLog", JSONObject()
                        .put("id", call.id).put("number", number).put("date", dateMs)
                        .put("systemDate", call.date).put("duration", duration).put("type", call.type))
                }
            }
        } catch (e: TimeoutCancellationException) {
            result.put("error", "等待系统拨号超时")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            result.put("error", e.message ?: "通话失败")
        } finally {
            recorder?.finish()?.let {
                // Android 本地诊断，与 H5 回调 JSON 分开，绝不把文件地址交给 H5 上传。
                binding.recordingStatus.text = it.error ?: if (it.file != null) {
                    "已封装，关联信息未完整时保留在临时区：${it.file.name}"
                } else "本次未产生录音"
            }
        }
        val audio = recorder?.finish()?.file
        if (audio != null && dateMs != null && duration != null) {
            try {
                val pending = withContext(Dispatchers.IO) {
                    (application as LabApplication).recordings.publish(audio, request.userId, number, dateMs, duration)
                }
                binding.recordingStatus.text = "已加入待上传目录：${pending.name}（实验上传接口尚未接入）"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.recordingStatus.text = "无法发布录音，原文件保留：${e.message}"
            }
        }
        return result
    }

    /** 这里就是移植到线上时调用 H5 的位置；callId 始终来自同一个不可变请求。 */
    private fun complete(request: Request, result: JSONObject) {
        result.put("callId", request.callId).put("number", request.number).put("mode", request.mode.name)
        binding.result.text = result.toString(2)
        binding.sessionStatus.text = "请求结束"
        Log.i("CallResult", result.toString())
    }

    private fun setBusy(busy: Boolean) {
        binding.dialButton.isEnabled = !busy
        binding.source.isEnabled = !busy
        binding.phoneNumber.isEnabled = !busy
        binding.userId.isEnabled = !busy
        binding.stopButton.isEnabled = busy && sdkEnded != null
    }
}
