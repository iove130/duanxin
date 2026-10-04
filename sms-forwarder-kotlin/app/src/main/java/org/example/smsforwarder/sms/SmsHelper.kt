package org.example.smsforwarder.sms

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.provider.Telephony
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.example.smsforwarder.core.SmsMessage as SmsMsg
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一张可用 SIM 卡的信息。 */
data class SimInfo(val slot: Int, val subsId: Int, val carrier: String)

/**
 * Android 侧能力封装。
 * 原生 API 直接调用，无需 JNI 桥接，比 Python+pyjnius 更稳更快。
 */
object SmsHelper {

    val REQUIRED_PERMISSIONS: Array<String> = arrayOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_PHONE_STATE,
    )

    fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** 危险权限是否都已授予（通知权限单独可选）。 */
    fun missingPermissions(context: Context): List<String> =
        REQUIRED_PERMISSIONS.filterNot { hasPermission(context, it) }

    fun missingShortNames(context: Context): List<String> =
        missingPermissions(context).map { it.substringAfterLast('.') }

    // ------------------------------------------------------------ 发送短信
    private fun smsManager(subsId: Int): SmsManager =
        if (subsId > 0) {
            runCatching { SmsManager.createForSubscriptionId(subsId) }.getOrNull()
                ?: SmsManager.getDefault()
        } else {
            SmsManager.getDefault()
        }

    /**
     * 发送一条短信。长短信交给底层 divideMessage 自动分段。
     * 返回 [是否成功, 附加说明/错误信息]。
     */
    fun sendSms(phone: String, text: String, subsId: Int = -1): Pair<Boolean, String> {
        val target = phone.trim()
        if (target.isEmpty()) return false to "接收人为空"
        if (text.isEmpty()) return false to "转发内容为空"

        return runCatching {
            val manager = smsManager(subsId)
            val parts = manager.divideMessage(text)
            if (parts.size <= 1) {
                manager.sendTextMessage(target, null, text, null, null)
                true to ""
            } else {
                manager.sendMultipartTextMessage(target, null, parts, null, null)
                true to "已分 ${parts.size} 条发送"
            }
        }.getOrElse { e ->
            false to "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // ------------------------------------------------------------ SIM 卡
    fun activeSims(context: Context): List<SimInfo> {
        if (!hasPermission(context, Manifest.permission.READ_PHONE_STATE)) return emptyList()
        return runCatching {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as? SubscriptionManager ?: return emptyList()
            val list: List<SubscriptionInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sm.activeSubscriptionInfoList ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                sm.activeSubscriptionInfoList ?: emptyList()
            }
            list.map {
                SimInfo(
                    slot = it.simSlotIndex,
                    subsId = it.subscriptionId,
                    carrier = it.carrierName?.toString().orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 尝试读出短信来自哪个 SIM 槽位，失败返回「未知」。 */
    fun simSlotOf(sms: SmsMessage, sims: List<SimInfo>): String {
        return runCatching {
            val sub = sms.subId
            sims.firstOrNull { it.subsId == sub }?.let {
                "卡${it.slot + 1}${if (it.carrier.isNotEmpty()) "(${it.carrier})" else ""}"
            }
        }.getOrNull() ?: "未知"
    }

    // ------------------------------------------------------------ 解析短信
    @SuppressLint("NewApi")
    fun parseSmsIntent(intent: Intent, sims: List<SimInfo>): List<SmsMsg> {
        val messages: Array<SmsMessage> = runCatching {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        }.getOrNull() ?: emptyArray()

        return messages.map { m ->
            SmsMsg(
                from = m.displayOriginatingAddress ?: m.originatingAddress.orEmpty(),
                body = m.messageBody.orEmpty(),
                time = formatTime(m.timestampMillis),
                sim = simSlotOf(m, sims),
            )
        }
    }

    private fun formatTime(millis: Long): String =
        runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(millis))
        }.getOrDefault(formatNow())

    fun formatNow(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    // ------------------------------------------------------------ 杂项
    fun toast(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    /** 跳转本应用系统详情页，方便手动开权限 / 关闭电池优化。 */
    fun openAppSettings(context: Context) {
        runCatching {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", context.packageName, null),
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /** 跳转电池优化白名单设置页（部分 ROM 需手动加白才能保活）。 */
    fun openBatterySettings(context: Context) {
        runCatching {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
