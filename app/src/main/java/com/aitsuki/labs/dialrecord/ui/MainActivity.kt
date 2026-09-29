package com.aitsuki.labs.dialrecord.ui

import android.Manifest.permission.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aitsuki.labs.dialrecord.accessibility.CallAccessibilityService
import com.aitsuki.labs.dialrecord.databinding.ActivityMainBinding
import com.aitsuki.labs.dialrecord.recording.RecordingFiles
import java.util.UUID

/** 页面只负责授权、输入校验和展示；请求交接与资源生命周期交给控制器。 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var calls: CallRequestController

    private val startupPermissions = buildList {
        addAll(listOf(CALL_PHONE, READ_PHONE_STATE, READ_CALL_LOG, RECORD_AUDIO))
        if (Build.VERSION.SDK_INT >= 33) add(POST_NOTIFICATIONS)
    }
    private var startupPermissionsGranted = false
    private var permissionRequestInFlight = false

    private val startupPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionRequestInFlight = false
            startupPermissionsGranted = startupPermissions.all(::hasPermission)
            if (startupPermissionsGranted) render(calls.state)
            else exitForMissingPermissions()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        calls = CallRequestController(this, ::render, ::notifyUser)
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
            val input = binding.sdkDuration.text.toString().trim()
            val seconds = input.toLongOrNull()
            if (input.isNotEmpty() && (seconds == null || seconds < 0)) {
                binding.sdkDuration.error = "Enter a non-negative integer, or leave blank for unknown"
            } else calls.endSimulation(seconds)
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
        render(calls.state)
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
        render(calls.state)
        notifyUser("All requested permissions are required. Closing the app.")
        finishAndRemoveTask()
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requestCall() {
        if (!startupPermissionsGranted || !calls.state.canDial) return
        // 号码是业务关联键：保留原始输入，不裁剪、不格式化；无效输入直接拒绝。
        val phoneNumber = binding.phoneNumber.text.toString()
        if (!Regex("\\+?[0-9]{1,32}").matches(phoneNumber)) {
            binding.phoneNumber.error = "Enter a valid phone number"
            return
        }
        val mode = CallMode.entries[binding.source.selectedItemPosition]
        val userId = binding.userId.text.toString().trim()
        if (mode.recordingEnabled && !RecordingFiles.validUserId(userId)) {
            binding.userId.error = "Enter a correlation userId (letters, digits, or hyphens)"
            return
        }
        // 保留系统录音实验的无障碍前置条件；不录音和 SDK 模拟不需要。
        if (mode == CallMode.SYSTEM_RECORD && !CallAccessibilityService.isConnected) {
            notifyUser("Enable the accessibility service, then try again")
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .onFailure { notifyUser("Unable to open accessibility settings") }
            return
        }
        calls.submit(CallRequest(UUID.randomUUID().toString(), phoneNumber, mode, userId))
    }

    /** 所有控件状态只在此处由请求快照推导，业务流程不直接操作按钮。 */
    private fun render(state: CallRequestController.State) {
        val canDial = startupPermissionsGranted && state.canDial
        binding.dialButton.isEnabled = canDial
        binding.source.isEnabled = canDial
        binding.phoneNumber.isEnabled = canDial
        binding.userId.isEnabled = canDial
        binding.stopButton.isEnabled = startupPermissionsGranted && state.canEndSimulation
        binding.sessionStatus.text = state.sessionStatus
        binding.recordingStatus.text = state.recordingStatus
        binding.result.text = state.result
    }

    private fun notifyUser(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
