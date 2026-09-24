package com.aitsuki.labs.dialrecord.ui

import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.provider.Settings
import android.telephony.PhoneNumberUtils
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import com.aitsuki.labs.dialrecord.accessibility.CallAccessibilityService
import com.aitsuki.labs.dialrecord.data.CallSource
import com.aitsuki.labs.dialrecord.data.PHONE_NUMBER_PATTERN
import com.aitsuki.labs.dialrecord.data.RecordingEntry
import com.aitsuki.labs.dialrecord.data.RecordingStore
import com.aitsuki.labs.dialrecord.databinding.ActivityMainBinding
import com.aitsuki.labs.dialrecord.recording.RecordingResult
import com.aitsuki.labs.dialrecord.recording.RecordingService
import java.lang.ref.WeakReference
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = RecordingAdapter { editDuration(it) }
    private var pendingRequest: Request? = null
    private var preparingId: String? = null
    private var activeRequest: Request? = null
    private var awaitingId: String? = null

    private data class Request(val source: CallSource, val phoneNumber: String)

    private class EventReceiver(activity: MainActivity) : ResultReceiver(Handler(Looper.getMainLooper())) {
        private val owner = WeakReference(activity)
        private val app = activity.applicationContext
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            if (resultCode != RecordingService.RESULT_EVENT || resultData == null) return
            val result = RecordingResult.fromBundle(resultData)
            val activity = owner.get()
            if (activity == null || activity.isDestroyed || activity.isFinishing) {
                if (result.event == RecordingResult.Event.READY) {
                    runCatching { RecordingService.cancelPreparation(app, result.recordingId) }
                }
                return
            }
            activity.onRecordingEvent(result)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val request = pendingRequest ?: return@registerForActivityResult
            pendingRequest = null
            if (RecordingService.requiredPermissions.all(::hasPermission)) prepare(request)
            else showToast("录音需要电话、电话状态、通话记录及麦克风权限")
        }

    private val refresh = object : Runnable {
        override fun run() {
            refreshRecordings()
            handler.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString("pendingSource")?.let {
            pendingRequest = Request(CallSource.valueOf(it),
                savedInstanceState.getString("pendingPhoneNumber").orEmpty())
        }
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
            listOf("系统电话（自动录音）", "SDK 通话（手动验证入口）"))
        binding.source.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                binding.dialButton.text = if (position == 0) "拨打电话并录音" else "开始录音"
            }
        }
        binding.recordingList.adapter = adapter
        binding.dialButton.setOnClickListener {
            val source = CallSource.entries[binding.source.selectedItemPosition]
            val rawPhoneNumber = binding.phoneNumber.text.toString().trim()
            val phoneNumber = PhoneNumberUtils.normalizeNumber(rawPhoneNumber)
            requestPermissionsAndPrepare(Request(source, phoneNumber))
        }
        binding.stopButton.setOnClickListener {
            RecordingService.session?.let {
                runCatching { RecordingService.finish(this, it.recordingId) }
                    .onFailure { showToast(it.message.orEmpty()) }
            }
        }
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsAndPrepare(request: Request) {
        if (pendingRequest != null || preparingId != null || RecordingService.hasSession) {
            showToast("请先结束当前录音会话")
            return
        }
        if (!PHONE_NUMBER_PATTERN.matches(request.phoneNumber)) {
            showToast("请输入有效的电话号码")
            return
        }
        val missing = RecordingService.requiredPermissions.filterNot(::hasPermission).toMutableList()
        // 每次操作都检查全部权限；通知拒绝后仍可继续，回调直接 prepare，避免申请循环。
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(POST_NOTIFICATIONS)) {
            missing += POST_NOTIFICATIONS
        }
        if (missing.isEmpty()) prepare(request)
        else {
            pendingRequest = request
            runCatching { permissionLauncher.launch(missing.toTypedArray()) }.onFailure {
                pendingRequest = null
                showToast("无法申请权限：${it.message}")
            }
        }
    }

    private fun prepare(request: Request) {
        if (RecordingService.hasSession || preparingId != null) return
        if (request.source == CallSource.SYSTEM && !CallAccessibilityService.isConnected) {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .onFailure { showToast("无法打开无障碍设置") }
            return
        }
        adapter.releasePlayer()
        val id = UUID.randomUUID().toString()
        preparingId = id
        awaitingId = id
        activeRequest = request
        runCatching { RecordingService.prepare(this, id, request.source, request.phoneNumber, EventReceiver(this)) }
            .onFailure {
                preparingId = null
                awaitingId = null
                activeRequest = null
                showToast("无法准备录音：${it.message}")
            }
    }

    private fun onRecordingEvent(result: RecordingResult) {
        if (result.recordingId != awaitingId) return
        val request = activeRequest
        when (result.event) {
            RecordingResult.Event.READY -> {
                preparingId = null
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || request == null) {
                    runCatching { RecordingService.cancelPreparation(this, result.recordingId) }
                    return
                }
                runCatching {
                    if (request.source == CallSource.SYSTEM) {
                        startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", request.phoneNumber, null)))
                    } else {
                        RecordingService.start(this, result.recordingId)
                    }
                }.onFailure {
                    runCatching { RecordingService.cancelPreparation(this, result.recordingId) }
                    showToast("无法开始通话录音：${it.message}")
                }
            }
            RecordingResult.Event.STARTED -> Unit
            else -> {
                preparingId = null
                awaitingId = null
                activeRequest = null
                showToast(when (result.event) {
                    RecordingResult.Event.COMPLETED -> "录音已保存，可随时更新通话时长"
                    RecordingResult.Event.CANCELLED -> result.errorMessage ?: "录音会话已取消"
                    else -> result.errorMessage ?: "录音失败"
                })
            }
        }
        refreshRecordings()
    }

    @SuppressLint("SetTextI18n") // 编辑值使用与 toLongOrNull 一致的整数格式。
    private fun editDuration(entry: RecordingEntry) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "通话时长（秒）"
            setText(entry.durationSeconds?.toString().orEmpty())
        }
        val dialog = AlertDialog.Builder(this).setTitle("更新通话时长").setView(input)
            .setMessage("此时长用于文件名，不代表音频长度。")
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val seconds = input.text.toString().toLongOrNull()
                if (seconds == null || seconds < 0) {
                    input.error = "请输入大于等于零的整数"
                    return@setOnClickListener
                }
                adapter.releasePlayer()
                runCatching { RecordingStore.updateDuration(this, entry.recordingId, seconds) }
                    .onSuccess { refreshRecordings(); dialog.dismiss() }
                    .onFailure { input.error = it.message }
            }
        }
        dialog.show()
    }

    private fun refreshRecordings() {
        runCatching { adapter.update(RecordingStore.loadEntries(this)) }
            .onFailure { showToast("无法读取录音：${it.message}") }
        val session = RecordingService.session
        binding.sessionStatus.text = when (session?.phase) {
            RecordingService.Phase.RECORDING -> "正在录音：${session.phoneNumber}"
            RecordingService.Phase.READY -> "等待通话或开始录音"
            RecordingService.Phase.PREPARING -> "正在准备"
            RecordingService.Phase.STOPPING -> "正在保存"
            null -> "当前没有录音会话"
        }
        binding.stopButton.isEnabled = session != null
        binding.dialButton.isEnabled = session == null && preparingId == null
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        adapter.releasePlayer()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingRequest?.let {
            outState.putString("pendingSource", it.source.name)
            outState.putString("pendingPhoneNumber", it.phoneNumber)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        preparingId?.let { runCatching { RecordingService.cancelPreparation(this, it) } }
        adapter.releasePlayer()
        binding.recordingList.adapter = null
        super.onDestroy()
    }

    private fun showToast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
