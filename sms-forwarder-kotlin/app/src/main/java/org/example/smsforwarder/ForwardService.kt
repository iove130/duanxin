package org.example.smsforwarder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.example.smsforwarder.core.Deduplicator
import org.example.smsforwarder.core.LogEntry
import org.example.smsforwarder.core.Rules
import org.example.smsforwarder.core.SmsMessage
import org.example.smsforwarder.data.ConfigStore
import org.example.smsforwarder.sms.SimInfo
import org.example.smsforwarder.sms.SmsHelper

/**
 * 短信转发常驻前台服务。
 *
 * 关键设计（对应 Python 版 service/main.py）：
 * 1. Android 8+ 静态注册 SMS_RECEIVED 对第三方应用基本失效，因此本服务
 *    **动态注册** BroadcastReceiver，并用前台服务把进程钉住，保证能收到短信。
 * 2. 收短信与发送短信都放到协程的 IO 线程，主线程只负责维持 Looper，
 *    避免广播回调被耗时操作饿死。
 * 3. 配置热重载：每次处理短信前都重新读取最新配置，改完设置立即生效。
 */
class ForwardService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: ConfigStore
    private val dedup = Deduplicator()
    private var smsReceiver: BroadcastReceiver? = null
    private var sims: List<SimInfo> = emptyList()

    // ------------------------------------------------ 兜底通道（短信库直读）
    private var observerThread: HandlerThread? = null
    private var smsObserver: ContentObserver? = null

    /** 已处理到的最新短信时间戳；启动时用当前库内最大值做基线，避免补发历史短信。 */
    private var lastSeenDate: Long = 0L

    /** 广播通道与数据库通道会同时看到同一条短信，用它做一次性去重。 */
    private val seenLock = Any()
    private val seenKeys = LinkedHashSet<String>()

    override fun onCreate() {
        super.onCreate()
        store = ConfigStore(this)
        store.setServiceShouldRun(true)
        startForegroundCompat()
        registerSmsReceiver()
        registerSmsObserver()
        startHeartbeat()
        // 先扫一次库，只为建立基线（不转发历史短信）
        serviceScope.launch { scanInbox() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForwarding()
            return START_NOT_STICKY
        }
        // START_STICKY：进程被系统回收后自动重建，继续转发
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 服务被系统回收属于非预期退出，重新置为「期望运行」，
        // 由 BootReceiver / 下次打开 App 时恢复，不要在这里清标志。
        store.setServiceShouldRun(true)
        unregisterSmsReceiver()
        unregisterSmsObserver()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 前台通知
    private fun startForegroundCompat() {
        createChannel()
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ForwardService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "停止", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.service_channel_desc)
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    // ------------------------------------------------------------ 短信监听
    private fun registerSmsReceiver() {
        if (smsReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != SMS_RECEIVED_ACTION) return
                val intentCopy = intent
                // 广播回调在主线程，快速切到 IO 线程做解析与发送
                serviceScope.launch {
                    handleSms(intentCopy)
                }
            }
        }
        val filter = IntentFilter(SMS_RECEIVED_ACTION).apply {
            // 最高优先级：抢在别的短信应用之前拿到广播（系统对普通应用限 999，这里用常量上限）
            @Suppress("DEPRECATION")
            priority = Int.MAX_VALUE
        }
        // Android 13+ 注册广播需要显式指定导出属性
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        smsReceiver = receiver
        log("info", "已注册短信监听器（前台服务运行中）")
    }

    private fun unregisterSmsReceiver() {
        smsReceiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        smsReceiver = null
    }

    // ------------------------------------------------ 兜底通道：短信库直读
    /**
     * 注册短信数据库监听。
     *
     * 小米 / 华为的「通知类短信保护」会在**广播层**就把验证码短信掐掉，
     * 第三方应用永远收不到 [SMS_RECEIVED_ACTION]。但短信依然会落进系统短信库，
     * 所以这里用 ContentObserver 直接监听库变化，补上这条链路。
     */
    private fun registerSmsObserver() {
        if (smsObserver != null) return
        if (!SmsHelper.hasPermission(this, Manifest.permission.READ_SMS)) {
            log("warn", "未授予读取短信权限，兜底通道未启用（验证码可能收不到）")
            return
        }
        val thread = HandlerThread("sms-db-observer").apply { start() }
        val observer = object : ContentObserver(Handler(thread.looper)) {
            override fun onChange(selfChange: Boolean) {
                serviceScope.launch { scanInbox() }
            }
        }
        runCatching {
            contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI,
                true, // 连同 sent / draft 等子 URI 一起监听
                observer,
            )
        }.onSuccess {
            observerThread = thread
            smsObserver = observer
            log("info", "已启用短信库兜底通道（验证码被系统拦截时仍可转发）")
        }.onFailure {
            log("warn", "兜底通道注册失败：${it.message}")
            thread.quitSafely()
        }
    }

    private fun unregisterSmsObserver() {
        smsObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        smsObserver = null
        observerThread?.quitSafely()
        observerThread = null
    }

    /**
     * 扫描短信库里新增的收件短信并按需转发。
     * 首次调用只建立基线，不转发，避免把历史验证码一次性补发出去。
     */
    private suspend fun scanInbox() {
        val all = SmsHelper.recentInbox(this, 10)
        if (all.isEmpty()) return

        if (lastSeenDate == 0L) {
            lastSeenDate = all.maxOf { it.rawDate }
            return
        }

        val fresh = all.filter { it.rawDate > lastSeenDate }.sortedBy { it.rawDate }
        if (fresh.isEmpty()) return
        // 先推进水位，即使当前暂停转发也不至于恢复后补发一批旧短信
        lastSeenDate = maxOf(lastSeenDate, fresh.maxOf { it.rawDate })

        val cfg = store.loadConfig()
        if (!cfg.enabled) return
        dedup.updateWindow(cfg.dedupMinutes)

        for (m in fresh) {
            runCatching { processOne(m, cfg) }
                .onFailure { log("error", "兜底通道处理异常: ${it.message}", m.from) }
        }
    }

    /** 同一条短信只处理一次（广播与短信库两条通道会重复看到它）。 */
    private fun markSeen(msg: SmsMessage): Boolean {
        val key = "${msg.from}|${msg.body}|${msg.rawDate}"
        synchronized(seenLock) {
            if (!seenKeys.add(key)) return false
            // 简单 LRU：超过 200 条就丢掉最早的一批
            if (seenKeys.size > 200) {
                val it = seenKeys.iterator()
                var n = 0
                while (it.hasNext() && n < 50) {
                    it.next()
                    it.remove()
                    n++
                }
            }
            return true
        }
    }

    // ------------------------------------------------------------ 心跳
    private fun startHeartbeat() {
        serviceScope.launch {
            while (isActive) {
                store.writeHeartbeat()
                delay(5_000)
            }
        }
    }

    // ------------------------------------------------------------ 核心流程
    private suspend fun handleSms(intent: Intent) {
        val cfg = store.loadConfig()
        dedup.updateWindow(cfg.dedupMinutes)
        sims = SmsHelper.activeSims(this)

        val messages = SmsHelper.parseSmsIntent(intent, sims)
        if (messages.isEmpty()) return

        // 多条 pdu 可能是同一条长短信的分片，拼起来再转发
        val items = if (messages.size > 1) {
            listOf(
                messages.first().copy(
                    body = messages.joinToString("") { it.body },
                ),
            )
        } else {
            messages
        }

        for (m in items) {
            // 广播通道先看��，兜底通道就不会重复转发同一条
            if (m.rawDate > lastSeenDate) lastSeenDate = m.rawDate
            runCatching { processOne(m, cfg) }
                .onFailure { log("error", "处理短信异常: ${it.message}", m.from) }
        }
    }

    private suspend fun processOne(msg: SmsMessage, cfg: org.example.smsforwarder.core.ForwardConfig) {
        val sender = msg.from
        val body = msg.body

        // 广播与短信库两条通道都会触发这里，先做一次性去重
        if (!markSeen(msg)) {
            log("skip", "[$sender] 同一条短信已处理，跳过", sender)
            return
        }

        val (shouldForward, reason, word) = Rules.shouldForward(msg, cfg)
        if (!shouldForward) {
            // 未命中时也记一笔，否则界面上看不到任何线索，无法排查
            log("skip", "[$sender] 未转发：$reason", sender)
            return
        }

        val receivers = cfg.receivers.filter { it.isNotBlank() }
        if (receivers.isEmpty()) {
            log("error", "命中但未配置接收人，请在 App 里设置接收人手机号", sender)
            return
        }

        if (dedup.isDuplicate(sender, body)) {
            log("skip", "去重命中，跳过转发 | $sender", sender)
            return
        }

        if (cfg.delaySeconds > 0) delay(cfg.delaySeconds * 1000L)

        // AUTO 模式且勾选「只发验证码」时，直接发纯数字；
        // 这样一条短信稳稳算 1 条（70 字内），不会因超长被拆成多条计费。
        val useRawCode = cfg.keywordMode == org.example.smsforwarder.core.KeywordMode.AUTO &&
            cfg.codeOnly &&
            word.isNotEmpty() &&
            word.all { it.isDigit() }

        val text = if (useRawCode) word else Rules.render(cfg.template, msg)
        if (useRawCode) log("info", "已提取验证码：$text", sender)
        for (phone in receivers) {
            if (cfg.splitSms && cfg.maxLen > 0 && text.length > cfg.maxLen) {
                for (part in Rules.splitText(text, cfg.maxLen, true)) {
                    val (okSend, err) = SmsHelper.sendSms(this, phone, part, cfg.subsId)
                    log(
                        if (okSend) "info" else "error",
                        "转发明细 -> $phone ${err.ifEmpty { "成功" }}",
                        sender,
                    )
                    if (!okSend) break
                    delay(300)
                }
            } else {
                val (okSend, err) = SmsHelper.sendSms(this, phone, text, cfg.subsId)
                log(
                    if (okSend) "info" else "error",
                    "转发 -> $phone ${if (err.isEmpty()) "成功" else "($err)"}",
                    sender,
                )
            }
        }
    }

    private fun log(level: String, msg: String, from: String = "", to: String = "") {
        Log.d(TAG, "[$level] $msg")
        runCatching {
            store.appendLog(
                LogEntry(
                    ts = SmsHelper.formatNow(),
                    level = level,
                    msg = msg,
                    from = from,
                    to = to,
                ),
            )
        }
    }

    private fun stopForwarding() {
        log("info", "收到停止指令，服务即将退出")
        store.setServiceShouldRun(false)
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        private const val TAG = "SmsForwarder"
        private const val CHANNEL_ID = "sms_forward_channel"
        private const val NOTIF_ID = 1001
        const val ACTION_START = "org.example.smsforwarder.START"
        const val ACTION_STOP = "org.example.smsforwarder.STOP"
        private const val SMS_RECEIVED_ACTION = "android.provider.Telephony.SMS_RECEIVED"

        fun start(context: Context) {
            val intent = Intent(context, ForwardService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ForwardService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
