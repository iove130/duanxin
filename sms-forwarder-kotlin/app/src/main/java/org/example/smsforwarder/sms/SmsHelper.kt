package org.example.smsforwarder.sms

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
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
    /**
     * 取指定卡槽的 SmsManager。
     * [SmsManager.createForSubscriptionId] 的静态方法在新版 SDK 已被隐藏/弃用，
     * 官方推荐从 [Context.getSystemService] 拿实例后再调实例方法，因此这里只走实例路径。
     */
    private fun smsManager(context: Context, subsId: Int): SmsManager {
        if (subsId <= 0) return SmsManager.getDefault()
        val base = runCatching { context.getSystemService(SmsManager::class.java) }.getOrNull()
            ?: return SmsManager.getDefault()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return SmsManager.getDefault()
        return runCatching { base.createForSubscriptionId(subsId) }.getOrDefault(base)
    }

    /**
     * 发送一条短信。长短信交给底层 divideMessage 自动分段。
     * 返回 [是否成功, 附加说明/错误信息]。
     */
    fun sendSms(context: Context, phone: String, text: String, subsId: Int = -1): Pair<Boolean, String> {
        val target = phone.trim()
        if (target.isEmpty()) return false to "接收人为空"
        if (text.isEmpty()) return false to "转发内容为空"

        return runCatching {
            val manager = smsManager(context, subsId)
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

    /**
     * 根据订阅 ID 判断短信来自哪个 SIM 卡槽位，失败返回「未知」。
     * 注意：SmsMessage 的 subId/subscriptionId 属于隐藏 API，直接引用会编译失败，
     * 因此订阅 ID 统一从广播 Intent 的 extra 中读取。
     */
    fun simSlotOf(subId: Int, sims: List<SimInfo>): String {
        if (subId <= 0 || sims.isEmpty()) return "未知"
        return sims.firstOrNull { it.subsId == subId }?.let {
            "卡${it.slot + 1}${if (it.carrier.isNotEmpty()) "(${it.carrier})" else ""}"
        } ?: "未知"
    }

    /** 从广播 Intent 中安全地取出订阅 ID（取不到返回 -1）。 */
    private fun subscriptionIdOf(intent: Intent): Int = runCatching {
        intent.getIntExtra("subscription", -1)
    }.getOrDefault(-1)

    // ------------------------------------------------------------ 解析短信
    @SuppressLint("NewApi")
    fun parseSmsIntent(intent: Intent, sims: List<SimInfo>): List<SmsMsg> {
        val messages: Array<SmsMessage> = runCatching {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        }.getOrNull() ?: emptyArray()

        val subId = subscriptionIdOf(intent)
        val simName = simSlotOf(subId, sims)

        return messages.map { m ->
            SmsMsg(
                from = m.displayOriginatingAddress ?: m.originatingAddress.orEmpty(),
                body = m.messageBody.orEmpty(),
                time = formatTime(m.timestampMillis),
                sim = simName,
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
