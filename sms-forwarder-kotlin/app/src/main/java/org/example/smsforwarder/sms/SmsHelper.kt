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
     * 取指定卡槽的SmsManager。
     * [SmsManager.createForSubscriptionId] 的静态方法在新版 SDK 已被隐藏/弃用，
     * 官方推荐从 [Context.getSystemService] 拿实例后再调实例方法，因此这里只走实例路径。
     */
    @Suppress("DEPRECATION")
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
                rawDate = m.timestampMillis,
            )
        }
    }

    /**
     * 兜底通道：直接读系统短信库里最近的收件短信。
     *
     * 小米 / 华为等 ROM 会对「验证码 / 通知类短信」做安全保护——
     * 这类短信**不会**广播给第三方应用，但仍然会写入短信数据库
     * （否则系统短信 App 自己也显示不出来）。
     * 因此这里绕开广播，用 ContentObserver + 查库的方式补上这条链路。
     *
     * 返回按时间倒序排列的收件短信（最新的在前）。
     */
    @SuppressLint("MissingPermission")
    fun recentInbox(context: Context, limit: Int = 10): List<SmsMsg> {
        if (!hasPermission(context, Manifest.permission.READ_SMS)) return emptyList()
        val out = ArrayList<SmsMsg>(limit)
        runCatching {
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                ),
                "${Telephony.Sms.TYPE} = ?",
                arrayOf(Telephony.Sms.MESSAGE_TYPE_INBOX.toString()),
                "${Telephony.Sms.DATE} DESC",
            ) ?: return@runCatching
            cursor.use { c ->
                val iAddr = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndex(Telephony.Sms.BODY)
                val iDate = c.getColumnIndex(Telephony.Sms.DATE)
                while (c.moveToNext() && out.size < limit) {
                    val date = if (iDate >= 0) c.getLong(iDate) else 0L
                    out.add(
                        SmsMsg(
                            from = if (iAddr >= 0) c.getString(iAddr).orEmpty() else "",
                            body = if (iBody >= 0) c.getString(iBody).orEmpty() else "",
                            time = formatTime(date),
                            sim = "未知",
                            rawDate = date,
                        ),
                    )
                }
            }
        }
        return out
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

    /** 读系统属性（MIUI / 澎湃 OS 通用，隐藏 API 用反射，失败返回空串）。 */
    private fun sysProp(key: String): String = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        get.invoke(null, key) as? String ?: ""
    }.getOrDefault("")

    /** ROM 代号，用于文案区分：澎湃 OS(HyperOS) / MIUI / 其他。 */
    fun romLabel(): String = when {
        sysProp("ro.mi.os.version.name").isNotEmpty() -> "澎湃OS(HyperOS)"
        sysProp("ro.miui.ui.version.name").isNotEmpty() -> "MIUI"
        Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) -> "小米"
        else -> ""
    }

    /**
     * 是否是小米系 ROM（MIUI 或澎湃 HyperOS）。
     *
     * 注意：澎湃 OS 已经不再暴露 `miui.os.Build`，只按 MIUI 特征判断会漏判，
     * 所以这里以厂商名为主、系统属性为辅，两者任一命中即认为是小米设备。
     *
     * 这些系统对「验证码 / 通知类短信」有独立的安全保护：
     * 普通短信广播能收到，验证码短信收不到，必须由用户在
     * 「设置 → 应用设置 → 应用管理 → 本应用 → 权限管理」里手动允许「通知类短信」。
     * 这是系统级授权，应用代码无法自行开启，只能检测并引导。
     */
    fun isXiaomiRom(): Boolean {
        val man = Build.MANUFACTURER.orEmpty()
        val brand = Build.BRAND.orEmpty()
        val byVendor = listOf(man, brand).any {
            it.equals("Xiaomi", ignoreCase = true) ||
                it.equals("Redmi", ignoreCase = true) ||
                it.equals("POCO", ignoreCase = true)
        }
        if (byVendor) return true
        return sysProp("ro.miui.ui.version.name").isNotEmpty() ||
            sysProp("ro.mi.os.version.name").isNotEmpty()
    }

    /** 验证码短信在小米系 ROM 上需要额外授权，这里给出明确引导文案。 */
    fun xiaomiCodeSmsHint(): String {
        val rom = romLabel().ifEmpty { "小米" }
        return "检测到 $rom：验证码属于「通知类短信」，系统默认不广播给第三方应用。\n" +
            "本应用已开启「短信库兜底通道」，多数机型无需额外设置即可转发验证码。\n" +
            "若日志里始终看不到验证码，再手动开启：\n" +
            "设置 → 应用设置 → 应用管理 → 短信转发器 → 权限管理 → 通知类短信 → 允许\n" +
            "（旧版 MIUI：安全中心 → 授权管理 → 应用权限 → 短信转发器 → 通知类短信）"
    }

    /** 尝试跳到小米授权管理页（澎湃 OS 与 MIUI 共用同一入口）；失败则退回应用详情页。 */
    fun openPermissionManager(context: Context) {
        val pkg = context.packageName
        // 三个入口按命中顺序尝试：新版安全中心 → 旧版权限中心 → 系统应用详情页
        val attempts = listOf(
            Triple(
                "miui.intent.action.APP_PERM_EDITOR",
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity",
            ),
            Triple(
                "miui.intent.action.APP_PERM_EDITOR_EXTRA",
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity",
            ),
            Triple(
                "android.settings.APPLICATION_DETAILS_SETTINGS",
                "com.android.settings",
                "com.android.settings.applications.InstalledAppDetailsTop",
            ),
        )
        var opened = false
        for ((action, pkgName, cls) in attempts) {
            val ok = runCatching {
                val intent = Intent(action).apply {
                    setClassName(pkgName, cls)
                    putExtra("extra_pkgname", pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }.isSuccess
            if (ok) {
                opened = true
                break
            }
        }
        if (!opened) openAppSettings(context)
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

    /**
     * 跳转开发者选项。
     *
     * 小米系 ROM 的「是否允许 XX 发送短信」确认弹窗由**系统优化（MIUI优化）**模块弹出，
     * 应用无法用代码关闭，只能让用户到开发者选项里把「启动系统优化」关掉。
     * 这个入口是系统级设置，权限再牛也绕不过去。
     */
    fun openDeveloperOptions(context: Context) {
        val opened = runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (!opened) openAppSettings(context)
    }
}
