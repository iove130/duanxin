package org.example.smsforwarder.ui

import android.Manifest
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.example.smsforwarder.ForwardService
import org.example.smsforwarder.core.ForwardConfig
import org.example.smsforwarder.core.KeywordMode
import org.example.smsforwarder.core.LogEntry
import org.example.smsforwarder.core.NetPreset
import org.example.smsforwarder.core.NetSender
import org.example.smsforwarder.core.Rules
import org.example.smsforwarder.core.SenderMode
import org.example.smsforwarder.data.ConfigStore
import org.example.smsforwarder.sms.SimInfo
import org.example.smsforwarder.sms.SmsHelper

/** UI 状态的一次性快照。 */
data class UiState(
    val serviceRunning: Boolean = false,
    val heartbeatText: String = "心跳：--",
    val permText: String = "检查中…",
    val logs: List<LogEntry> = emptyList(),
    val sims: List<SimInfo> = emptyList(),
    val draft: ForwardConfig = ForwardConfig.DEFAULT,
    /** 草稿与已保存配置是否有差异——用于顶部「未保存」提醒。 */
    val dirty: Boolean = false,
)

/**
 * UI 状态管理。表单以「草稿」形式持有，保存后写入 SharedPreferences，
 * 服务每次处理短信前都会重新读取最新配置，实现热生效。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ConfigStore(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 已落盘的配置快照，用于算「有没有未保存的修改」。 */
    private var savedSnapshot: ForwardConfig = store.loadConfig()

    init {
        loadDraft()
        refresh()
        // 定时刷新服务状态与日志。
        // 放到 IO 线程：readLogs() 要解析整份 JSON 日志（最多 300 条），
        // 放主线程会周期性掉帧，手势会明显发涩。
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1500)
                refresh()
            }
        }
    }

    private fun loadDraft() {
        val loaded = store.loadConfig()
        savedSnapshot = loaded
        _state.value = _state.value.copy(
            draft = loaded,
            sims = SmsHelper.activeSims(getApplication()),
            permText = permTextOf(),
            // 草稿与已保存配置可能因历史数据格式不同而不一致，启动时先对齐一次，
            // 否则一进来顶部就报「有未保存的修改」，是假信号。
            dirty = false,
        )
    }

    private fun permTextOf(): String {
        val missing = SmsHelper.missingPermissions(getApplication())
        return if (missing.isEmpty()) {
            "短信权限已授予"
        } else {
            "未授权：" + SmsHelper.missingShortNames(getApplication()).take(3).joinToString(", ")
        }
    }

    fun refresh() {
        val alive = store.isServiceAlive()
        val hb = store.readHeartbeat()
        val hbText = if (hb <= 0) {
            "服务未启动"
        } else {
            val secs = (System.currentTimeMillis() - hb) / 1000
            if (alive) "$secs 秒前活跃 · 进程存活" else "最后活跃在 $secs 秒前 · 进程可能已掉线"
        }
        _state.value = _state.value.copy(
            serviceRunning = alive,
            heartbeatText = hbText,
            permText = permTextOf(),
            logs = store.readLogs().takeLast(40).reversed(),
            dirty = _state.value.draft != savedSnapshot,
        )
    }

    // ------------------------------------------------------------ 表单编辑
    fun update(transform: (ForwardConfig) -> ForwardConfig) {
        val next = transform(_state.value.draft)
        _state.value = _state.value.copy(draft = next, dirty = next != savedSnapshot)
    }

    fun save(): String {
        store.saveConfig(_state.value.draft)
        savedSnapshot = _state.value.draft
        _state.value = _state.value.copy(dirty = false)
        refresh()
        return "配置已保存，服务会自动生效"
    }

    /** 一键保存并启动——新用户最常见的诉求，不该让他分两步做。 */
    fun saveAndStart(): String {
        save()
        val msg = startService()
        return if (msg == "转发服务已启动") "配置已保存，转发服务已启动" else msg
    }

    fun startService(): String {
        val ctx = getApplication<Application>()
        val missing = SmsHelper.missingPermissions(ctx)
        if (missing.isNotEmpty()) {
            return "缺少短信权限，请先授权"
        }
        // 启动前先落盘配置，确保服务立刻用最新规则
        store.saveConfig(_state.value.draft)
        savedSnapshot = _state.value.draft
        _state.value = _state.value.copy(dirty = false)
        store.setServiceShouldRun(true)
        runCatching { ForwardService.start(ctx) }
            .onFailure { return "启动失败：${it.message}" }
        return "转发服务已启动"
    }

    fun stopService(): String {
        val ctx = getApplication<Application>()
        store.setServiceShouldRun(false)
        runCatching { ForwardService.stop(ctx) }
        return "已发送停止指令"
    }

    fun testForward(): String {
        val ctx = getApplication<Application>()
        val cfg = _state.value.draft
        val receiver = cfg.receivers.firstOrNull { it.isNotBlank() }
        if (receiver == null) return "请先填写接收人手机号"
        if (SmsHelper.missingPermissions(ctx).isNotEmpty()) {
            return "缺少短信权限，无法发送"
        }
        val text = "【短信转发器】测试消息 " +
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
        val (ok, err) = SmsHelper.sendSms(ctx, receiver, text, cfg.subsId)
        return if (ok) "已发送测试短信" else "发送失败 $err"
    }

    fun clearLogs(): String {
        store.clearLogs()
        refresh()
        return "日志已清空"
    }

    fun openSettings(): Boolean {
        SmsHelper.openAppSettings(getApplication())
        return true
    }

    fun openBatterySettings(): Boolean {
        SmsHelper.openBatterySettings(getApplication())
        return true
    }

    /** 小米系 ROM（MIUI / 澎湃OS）验证码短信需额外授权时返回引导文案，否则空串。 */
    fun romHint(): String = if (SmsHelper.isXiaomiRom()) SmsHelper.xiaomiCodeSmsHint() else ""

    fun isXiaomiRom(): Boolean = SmsHelper.isXiaomiRom()

    fun openPermissionManager(): Boolean {
        SmsHelper.openPermissionManager(getApplication())
        return true
    }

    /** 跳转开发者选项——关闭「启动系统优化」才能去掉发送短信的确认弹窗。 */
    fun openDeveloperOptions(): Boolean {
        SmsHelper.openDeveloperOptions(getApplication())
        return true
    }

    /**
     * 自检：能否绕过广播直接读到系统短信库。
     * 结果写进日志面板——用户点一下就知道「兜底通道」在自己的机型上是否可用。
     */
    fun probeInbox() {
        // 读短信库是 IO 操作，必须在后台线程，否则阻塞主线程
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val msg = if (!SmsHelper.hasPermission(ctx, Manifest.permission.READ_SMS)) {
                "自检失败：未授予「读取短信」权限，兜底通道无法启用"
            } else {
                val list = SmsHelper.recentInbox(ctx, 5)
                if (list.isEmpty()) {
                    "自检失败：读不到任何短信。当前 ROM 禁止第三方应用访问短信库，" +
                        "只能走广播通道——请确认已在系统设置里允许「通知类短信」"
                } else {
                    "自检通过：读到 ${list.size} 条收件短信，最新来自 ${list.first().from}，" +
                        "兜底通道可用（验证码被系统拦截时仍能转发）"
                }
            }
            appendLog(msg)
        }
    }

    // ------------------------------------------------------------ 便捷更新
    fun setReceivers(text: String) = update { it.copy(receivers = Rules.parseList(text)) }
    fun setSenders(text: String) = update { it.copy(senders = Rules.parseList(text)) }
    fun setKeywords(text: String) = update { it.copy(keywords = Rules.parseList(text)) }
    fun setExclude(text: String) = update { it.copy(excludeKeywords = Rules.parseList(text)) }
    fun setTemplate(text: String) = update { it.copy(template = text) }
    // 数字类字段统一在这里做区间夹紧，UI 层只负责「能不能解析成整数」。
    // 不夹紧的后果：maxLen=0 会让 splitText 走「不切」分支，去重窗口为负
    // 会让时间比较恒真从而永久去重——都是很难从界面上看出原因的坏状态。
    fun setMaxLen(v: Int) = update { it.copy(maxLen = v.coerceIn(1, 5000)) }
    fun setDedup(v: Int) = update { it.copy(dedupMinutes = v.coerceIn(0, 1440)) }
    fun setDelay(v: Int) = update { it.copy(delaySeconds = v.coerceIn(0, 600)) }
    fun setSenderMode(mode: SenderMode) = update { it.copy(senderMode = mode) }
    fun setKeywordMode(mode: KeywordMode) = update { it.copy(keywordMode = mode) }
    fun setCaseSensitive(v: Boolean) = update { it.copy(caseSensitive = v) }
    fun setSplitSms(v: Boolean) = update { it.copy(splitSms = v) }
    fun setEnabled(v: Boolean) = update { it.copy(enabled = v) }
    fun setSubsId(id: Int) = update { it.copy(subsId = id) }
    fun setCodeOnly(v: Boolean) = update { it.copy(codeOnly = v) }
    fun setNetEnabled(v: Boolean) = update { it.copy(netEnabled = v) }
    fun setNetPreset(v: NetPreset) = update { it.copy(netPreset = v) }
    fun setNetUrl(v: String) = update { it.copy(netUrl = v.trim()) }

    /**
     * 联网推送自检：发一个固定测试码 000000。
     * 结果写进日志面板，方便确认 Key/URL 填对了没。
     */
    fun testNetPush() {
        // 网络请求必须在后台线程，否则 NetworkOnMainThreadException
        viewModelScope.launch(Dispatchers.IO) {
            val cfg = store.loadConfig()
            val req = NetSender.build(cfg.netPreset, cfg.netUrl, "000000")
            val msg = when {
                !cfg.netEnabled -> "联网推送未启用，请先打开开关并保存"
                req == null -> "联网推送配置无效：${cfg.netPreset.label} 的 Key/URL 为空"
                else -> {
                    val (ok, err) = NetSender.send(req)
                    if (ok) "联网推送自检成功（已发测试码 000000 到 ${cfg.netPreset.label}）"
                    else "联网推送自检失败：$err"
                }
            }
            appendLog(msg)
        }
    }

    private fun appendLog(msg: String) {
        store.appendLog(
            LogEntry(ts = SmsHelper.formatNow(), level = "info", msg = msg),
        )
        refresh()
    }
}
