package org.example.smsforwarder

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import org.example.smsforwarder.sms.SmsHelper
import org.example.smsforwarder.ui.MainScreen
import org.example.smsforwarder.ui.theme.SmsForwarderTheme

class MainActivity : ComponentActivity() {

    // 使用官方 Activity Result API 申请权限
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // 授权结果由 Compose 侧通过 refresh() 读取，无需额外处理
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // 首次进入自动申请缺失权限
        maybeRequestPermissions()

        setContent {
            SmsForwarderTheme {
                MainScreen(
                    onRequestPermissions = { requestPermissions() },
                )
            }
        }
    }

    private fun maybeRequestPermissions() {
        // 一次性把所有缺失权限合并成一次请求：
        // ActivityResultLauncher 在上一次结果回调前再次 launch 会抛 IllegalStateException。
        val wanted = ArrayList<String>()
        wanted += SmsHelper.missingPermissions(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val post = android.Manifest.permission.POST_NOTIFICATIONS
            if (SmsHelper.hasPermission(this, post).not()) wanted += post
        }
        if (wanted.isEmpty()) return
        runCatching { permissionLauncher.launch(wanted.toTypedArray()) }
    }

    private fun requestPermissions() {
        val missing = SmsHelper.missingPermissions(this)
        if (missing.isNotEmpty()) {
            runCatching { permissionLauncher.launch(missing.toTypedArray()) }
        }
    }
}
