package com.aitsuki.labs.dialrecord

import android.Manifest
import android.Manifest.permission.CALL_PHONE
import android.Manifest.permission.READ_CALL_LOG
import android.Manifest.permission.READ_PHONE_STATE
import android.Manifest.permission.RECORD_AUDIO
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aitsuki.labs.dialrecord.databinding.ActivityMainBinding
import com.aitsuki.labs.dialrecord.recording.CallAccessibilityService
import com.aitsuki.labs.dialrecord.recording.CallRequest
import com.aitsuki.labs.dialrecord.recording.CallRequestController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID

/** 页面只负责授权、输入校验和展示；请求交接与资源生命周期交给控制器。 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val logListener: (String) -> Unit = { logs ->
        val scroll = binding.logScroll
        val followLatest = !scroll.canScrollVertically(1)
        binding.logText.text = logs
        if (followLatest) {
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private val requiredPermissions =
        listOf(CALL_PHONE, READ_CALL_LOG, RECORD_AUDIO, READ_PHONE_STATE)
    private var permissionFlowInFlight = false
    private var permissionDialog: AlertDialog? = null
    private val callRequests = CallRequestController(this) { request, result ->
        AppLog.info("Call result ${request.callId}: $result")
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionFlowInFlight = false
            handlePermissionResult()
        }

    private val permissionSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            permissionFlowInFlight = false
            handlePermissionResult()
        }

    private fun handlePermissionResult() {
        if (isFinishing || isDestroyed || permissionFlowInFlight) return
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            permissionDialog?.dismiss()
            permissionDialog = null
            AppLog.debug("all permission granted!")
            return
        }
        if (permissionDialog?.isShowing == true) return

        val neverAsks = missing.filter {
            !ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }

        // 用户拒绝了权限，且没有永久拒绝的权限时，重新请求权限
        if (neverAsks.isEmpty()) {
            permissionFlowInFlight = true
            permissionLauncher.launch(missing.toTypedArray())
            return
        }

        // 存在永久拒绝的权限时，引导去设置页面
        permissionDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Tips")
            .setCancelable(false)
            .setMessage("Please granted permissions")
            .setPositiveButton("Go to settings") { dialog, _ ->
                dialog.dismiss()
                permissionDialog = null
                permissionFlowInFlight = true
                permissionSettingsLauncher.launch(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                )
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionFlowInFlight = savedInstanceState?.getBoolean("permissionFlowInFlight") ?: false
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
        binding.dialButton.setOnClickListener {
            binding.phoneNumber.clearFocus()
            dial(binding.phoneNumber.text.toString().trim(), UUID.randomUUID().toString())
        }
        if (!permissionFlowInFlight) {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requiredPermissions + Manifest.permission.POST_NOTIFICATIONS
            } else {
                requiredPermissions
            }
            permissionFlowInFlight = true
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun dial(number: String, callId: String) {
        try {
            if (number.isEmpty() || callId.isEmpty()) return
            submitCallRequest(
                CallRequest(
                    callId = callId,
                    phoneNumber = number,
                    needRecord = true,
                    userId = "123456",
                )
            )
        } catch (e: Exception) {
            AppLog.debug("dial error", e)
        }
    }

    private fun submitCallRequest(request: CallRequest) {
        if (!callRequests.canSubmit) {
            AppLog.warn(
                "拒绝拨号入口请求：callId=%s，原因=%s", request.callId,
                "系统通话请求占用（${callRequests.activeRequestDescription}）"
            )
            return
        }
        if (request.needRecord && !CallAccessibilityService.isConnected) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        callRequests.submit(request)
    }

    override fun onStart() {
        super.onStart()
        AppLog.addListener(logListener)
    }

    override fun onStop() {
        AppLog.removeListener(logListener)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("permissionFlowInFlight", permissionFlowInFlight)
    }

    override fun onDestroy() {
        permissionDialog?.dismiss()
        permissionDialog = null
        super.onDestroy()
    }
}