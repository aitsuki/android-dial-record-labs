package com.aitsuki.labs.dialrecord.ui

import android.Manifest.permission.CALL_PHONE
import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.READ_CALL_LOG
import android.Manifest.permission.READ_PHONE_STATE
import android.Manifest.permission.RECORD_AUDIO
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
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import com.aitsuki.labs.dialrecord.accessibility.CallAccessibilityService
import com.aitsuki.labs.dialrecord.data.RecordingStore
import com.aitsuki.labs.dialrecord.databinding.ActivityMainBinding
import com.aitsuki.labs.dialrecord.recording.CallSessionService
import java.util.UUID

private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val requiredPermissions = arrayOf(CALL_PHONE, READ_CALL_LOG, RECORD_AUDIO, READ_PHONE_STATE)
    private var numberAwaitingPermissions: String? = null
    private var notificationPermissionRequested = false

    private var preparingSessionToken: String? = null // 等待服务准备完成，尚未打开系统拨号界面。
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = RecordingAdapter()

    private val refreshRecordingsTask = object : Runnable {
        override fun run() {
            adapter.update(
                RecordingStore.directory(this@MainActivity).listFiles()
                    ?.filter { it.isFile && it.extension == "m4a" }
                    ?.sortedByDescending { it.name.substringAfter('_') }
                    .orEmpty(),
                RecordingStore.loadEntries(this@MainActivity),
            )
            handler.postDelayed(this, 2_000)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val number = numberAwaitingPermissions ?: return@registerForActivityResult
            numberAwaitingPermissions = null
            if (requiredPermissions.all { hasPermission(it) }) {
                dial(number)
            } else {
                showToast("拨号录音需要电话、麦克风及通话记录权限")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        numberAwaitingPermissions = savedInstanceState?.getString("numberAwaitingPermissions")
        notificationPermissionRequested =
            savedInstanceState?.getBoolean("notificationPermissionRequested") ?: false
        Log.d(TAG, "onCreate: numberAwaitingPermissions=$numberAwaitingPermissions")
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val padding = (24 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars =
                insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(
                padding + bars.left,
                padding + bars.top,
                padding + bars.right,
                padding + bars.bottom
            )
            insets
        }
        binding.recordingList.adapter = adapter
        binding.dialButton.setOnClickListener {
            dial(binding.phoneNumber.text.toString())
        }
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** All dial entry points share validation and session guards, independently of the button. */
    private fun dial(rawNumber: String) {
        // Serialize callers on the main thread, including future non-UI entry points.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { dial(rawNumber) }
            return
        }
        Log.d(TAG, "dial: number=$rawNumber")
        if (isFinishing || isDestroyed) {
            Log.w(TAG, "dial: 页面已结束 isFinishing=$isFinishing, isDestroyed=$isDestroyed")
            return
        }

        // preparingSessionToken 只保护服务准备回调，不表示通话状态。
        if (preparingSessionToken != null) {
            Log.d(TAG, "dial: 等待服务准备回调，忽略重复提交 preparingSessionToken=$preparingSessionToken")
            showToast("正在准备拨号，请稍后再试")
            return
        }
        if (!CallSessionService.canStartDial) {
            Log.d(TAG, "dial: 会话正在通话或收尾，拒绝新请求")
            showToast("正在通话或处理录音，请稍后再试")
            return
        }

        val number = PhoneNumberUtils.normalizeNumber(rawNumber.trim())
        if (!Regex("\\+?[0-9]{1,32}").matches(number)) {
            showToast("请输入有效的电话号码（支持国际区号）")
            return
        }
        val missing = requiredPermissions.filterNot { hasPermission(it) }.toMutableList()
        // Optional: denial must not cause a request loop when the permission callback re-enters dial.
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(POST_NOTIFICATIONS) && !notificationPermissionRequested) {
            missing += POST_NOTIFICATIONS
        }
        if (missing.isNotEmpty()) {
            numberAwaitingPermissions = number
            if (POST_NOTIFICATIONS in missing) {
                notificationPermissionRequested = true
            }
            runCatching { permissionLauncher.launch(missing.toTypedArray()) }.onFailure {
                numberAwaitingPermissions = null
                showToast("无法申请拨号权限：${it.message}")
            }
            return
        }
        if (!CallAccessibilityService.isConnected) {
            runCatching {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.onFailure {
                Log.e(TAG, "dial: 无法打开无障碍设置", it)
                showToast("无法打开无障碍设置")
            }
            return
        }
        adapter.releasePlayer()
        val sessionToken = UUID.randomUUID().toString()
        preparingSessionToken = sessionToken
        val preparationReceiver = object : ResultReceiver(handler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                Log.d(
                    TAG,
                    "onReceiveResult: resultCode=$resultCode, preparingSessionToken=$preparingSessionToken, sessionToken=$sessionToken"
                )
                if (preparingSessionToken != sessionToken) {
                    Log.w(TAG, "onReceiveResult: 忽略非当前准备会话的回调 sessionToken=$sessionToken")
                    return
                }
                preparingSessionToken = null
                if (resultCode != CallSessionService.RESULT_SESSION_READY) {
                    val errorMessage =
                        resultData?.getString(CallSessionService.EXTRA_ERROR_MESSAGE) ?: "无法启动录音服务"
                    Log.w(TAG, "onReceiveResult: 服务启动失败，sessionToken=$sessionToken, error=$errorMessage")
                    showToast(errorMessage)
                    return
                }

                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    Log.w(TAG, "onReceiveResult: 服务准备期间离开页面，取消等待会话 sessionToken=$sessionToken")
                    cancelWaitingSession(sessionToken)
                    return
                }
                runCatching {
                    Log.d(TAG, "onReceiveResult: 开始拨号 $number, sessionToken=$sessionToken")
                    startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)))
                }.onFailure {
                    Log.e(TAG, "onReceiveResult: 拨号失败 $number, sessionToken=$sessionToken", it)
                    cancelWaitingSession(sessionToken)
                    showToast("拨号失败：${it.message}")
                }
            }
        }

        Log.d(TAG, "dial: 正在启动录音前台服务, number = $number, sessionToken=$sessionToken")
        runCatching {
            ContextCompat.startForegroundService(
                this, Intent(this, CallSessionService::class.java)
                    .setAction(CallSessionService.ACTION_PREPARE_SESSION)
                    .putExtra(CallSessionService.EXTRA_NUMBER, number)
                    .putExtra(CallSessionService.EXTRA_SESSION_TOKEN, sessionToken)
                    .putExtra(CallSessionService.EXTRA_PREPARATION_RECEIVER, preparationReceiver)
            )
        }.onFailure {
            preparingSessionToken = null
            Log.e(TAG, "dial: 启动录音前台服务失败, number = $number, sessionToken=$sessionToken", it)
            showToast("无法启动录音服务：${it.message}")
        }
    }

    private fun cancelWaitingSession(sessionToken: String) {
        Log.d(TAG, "cancelWaitingSession: sessionToken=$sessionToken")
        runCatching {
            startService(
                Intent(this, CallSessionService::class.java)
                    .setAction(CallSessionService.ACTION_CANCEL_WAITING_SESSION)
                    .putExtra(CallSessionService.EXTRA_SESSION_TOKEN, sessionToken)
            )
        }.onFailure {
            Log.e(TAG, "无法取消等待拨号的会话 sessionToken=$sessionToken", it)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: preparingSessionToken=$preparingSessionToken")
        handler.removeCallbacks(refreshRecordingsTask)
        handler.post(refreshRecordingsTask)
    }

    override fun onPause() {
        Log.d(TAG, "onPause: preparingSessionToken=$preparingSessionToken")
        handler.removeCallbacks(refreshRecordingsTask)
        adapter.releasePlayer()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("numberAwaitingPermissions", numberAwaitingPermissions)
        outState.putBoolean("notificationPermissionRequested", notificationPermissionRequested)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: preparingSessionToken=$preparingSessionToken")
        // 页面销毁时，取消尚未收到准备回调的会话；服务只清理匹配且仍在等待通话的会话。
        preparingSessionToken?.let { cancelWaitingSession(it) }
        preparingSessionToken = null
        adapter.releasePlayer()
        binding.recordingList.adapter = null
        super.onDestroy()
    }

    private fun showToast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

}
