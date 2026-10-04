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
        if (SmsHelper.missingPermissions(this).isNotEmpty()) {
            permissionLauncher.launch(SmsHelper.REQUIRED_PERMISSIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            )
        }
    }

    private fun requestPermissions() {
        val missing = SmsHelper.REQUIRED_PERMISSIONS
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing)
        }
    }
}
