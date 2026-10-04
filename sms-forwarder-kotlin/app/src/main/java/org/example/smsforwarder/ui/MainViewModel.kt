package org.example.smsforwarder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
)

/**
 * UI 状态管理。表单以「草稿」形式持有，保存后写入 SharedPreferences，
 * 服务每次处理短信前都会重新读取最新配置，实现热生效。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ConfigStore(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        loadDraft()
        refresh()
        // 定时刷新服务状态与日志
        viewModelScope.launch {
            while (isActive) {
                delay(1500)
                refresh()
            }
        }
    }

    private fun loadDraft() {
        _state.value = _state.value.copy(
            draft = store.loadConfig(),
            sims = SmsHelper.activeSims(getApplication()),
        )
    }

    fun refresh() {
        val ctx = getApplication<Application>()
        val alive = store.isServiceAlive()
        val hb = store.readHeartbeat()
        val hbText = if (hb <= 0) {
            "心跳：--"
        } else {
            val secs = (System.currentTimeMillis() - hb) / 1000
            if (alive) "心跳：${secs} 秒前（进程存活）" else "上次心跳：${secs} 秒前（已掉线）"
        }
        val permText = if (SmsHelper.missingPermissions(ctx).isEmpty()) {
            "短信权限已授予"
        } else {
            "未授权：" + SmsHelper.missingShortNames(ctx).take(3).joinToString(", ")
        }
        _state.value = _state.value.copy(
            serviceRunning = alive,
            heartbeatText = hbText,
            permText = permText,
            logs = store.readLogs().takeLast(40).reversed(),
        )
    }

    // ------------------------------------------------------------ 表单编辑
    fun update(transform: (ForwardConfig) -> ForwardConfig) {
        _state.value = _state.value.copy(draft = transform(_state.value.draft))
    }

    fun save(): String {
        store.saveConfig(_state.value.draft)
        refresh()
        return "配置已保存，服务会自动生效"
    }

    fun startService(): String {
        val ctx = getApplication<Application>()
        val missing = SmsHelper.missingPermissions(ctx)
        if (missing.isNotEmpty()) {
            return "缺少短信权限，请先授权"
        }
        // 启动前先落盘配置，确保服务立刻用最新规则
        store.saveConfig(_state.value.draft)
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

    /** MIUI 验证码短信需要额外授权，返回引导文案；非 MIUI 返回空串。 */
    fun miuiHint(): String = if (SmsHelper.isMiui()) SmsHelper.miuiCodeSmsHint() else ""

    fun openPermissionManager(): Boolean {
        SmsHelper.openPermissionManager(getApplication())
        return true
    }

    // ------------------------------------------------------------ 便捷更新
    fun setReceivers(text: String) = update { it.copy(receivers = Rules.parseList(text)) }
    fun setSenders(text: String) = update { it.copy(senders = Rules.parseList(text)) }
    fun setKeywords(text: String) = update { it.copy(keywords = Rules.parseList(text)) }
    fun setExclude(text: String) = update { it.copy(excludeKeywords = Rules.parseList(text)) }
    fun setTemplate(text: String) = update { it.copy(template = text) }
    fun setMaxLen(text: String) = update { it.copy(maxLen = text.toIntOrNull() ?: 500) }
    fun setDedup(text: String) = update { it.copy(dedupMinutes = text.toIntOrNull() ?: 0) }
    fun setDelay(text: String) = update { it.copy(delaySeconds = text.toIntOrNull() ?: 0) }
    fun setSenderMode(mode: SenderMode) = update { it.copy(senderMode = mode) }
    fun setKeywordMode(mode: KeywordMode) = update { it.copy(keywordMode = mode) }
    fun setCaseSensitive(v: Boolean) = update { it.copy(caseSensitive = v) }
    fun setSplitSms(v: Boolean) = update { it.copy(splitSms = v) }
    fun setEnabled(v: Boolean) = update { it.copy(enabled = v) }
    fun setSubsId(id: Int) = update { it.copy(subsId = id) }
    fun setCodeOnly(v: Boolean) = update { it.copy(codeOnly = v) }
}
