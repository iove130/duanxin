package org.example.smsforwarder.data

import android.content.Context
import android.content.SharedPreferences
import org.example.smsforwarder.core.ForwardConfig
import org.example.smsforwarder.core.KeywordMode
import org.example.smsforwarder.core.LogEntry
import org.example.smsforwarder.core.NetPreset
import org.example.smsforwarder.core.SenderMode
import org.example.smsforwarder.core.Rules
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置与日志的本地存储（SharedPreferences）。
 *
 * 原生 Android 的 UI 进程与前台服务同属一个进程，无需像 Python 版那样
 * 跨进程读写 JSON 文件 —— SharedPreferences 天然线程安全、原子、可靠。
 * 数据只落在本机沙盒内，且应用未申请网络权限，不会有外传风险。
 */
class ConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ 配置
    fun loadConfig(): ForwardConfig {
        val d = ForwardConfig.DEFAULT
        return ForwardConfig(
            enabled = prefs.getBoolean(K_ENABLED, d.enabled),
            receivers = prefs.getStringSet(K_RECEIVERS, emptySet()).orEmpty().toList(),
            senders = prefs.getStringSet(K_SENDERS, emptySet()).orEmpty().toList(),
            senderMode = SenderMode.fromKey(prefs.getString(K_SENDER_MODE, d.senderMode.key)),
            keywords = prefs.getStringSet(K_KEYWORDS, emptySet()).orEmpty().toList(),
            keywordMode = KeywordMode.fromKey(prefs.getString(K_KEYWORD_MODE, d.keywordMode.key)),
            caseSensitive = prefs.getBoolean(K_CASE_SENSITIVE, d.caseSensitive),
            excludeKeywords = prefs.getStringSet(K_EXCLUDE, emptySet()).orEmpty().toList(),
            template = prefs.getString(K_TEMPLATE, d.template) ?: d.template,
            maxLen = prefs.getInt(K_MAX_LEN, d.maxLen),
            splitSms = prefs.getBoolean(K_SPLIT, d.splitSms),
            dedupMinutes = prefs.getInt(K_DEDUP, d.dedupMinutes),
            delaySeconds = prefs.getInt(K_DELAY, d.delaySeconds),
            subsId = prefs.getInt(K_SUBS_ID, d.subsId),
            logLimit = prefs.getInt(K_LOG_LIMIT, d.logLimit),
            codeOnly = prefs.getBoolean(K_CODE_ONLY, d.codeOnly),
            netEnabled = prefs.getBoolean(K_NET_ENABLED, d.netEnabled),
            netPreset = NetPreset.fromKey(prefs.getString(K_NET_PRESET, d.netPreset.key)),
            netUrl = prefs.getString(K_NET_URL, d.netUrl) ?: d.netUrl,
        )
    }

    fun saveConfig(cfg: ForwardConfig) {
        prefs.edit().apply {
            putBoolean(K_ENABLED, cfg.enabled)
            putStringSet(K_RECEIVERS, cfg.receivers.toSet())
            putStringSet(K_SENDERS, cfg.senders.toSet())
            putString(K_SENDER_MODE, cfg.senderMode.key)
            putStringSet(K_KEYWORDS, cfg.keywords.toSet())
            putString(K_KEYWORD_MODE, cfg.keywordMode.key)
            putBoolean(K_CASE_SENSITIVE, cfg.caseSensitive)
            putStringSet(K_EXCLUDE, cfg.excludeKeywords.toSet())
            putString(K_TEMPLATE, cfg.template)
            putInt(K_MAX_LEN, cfg.maxLen)
            putBoolean(K_SPLIT, cfg.splitSms)
            putInt(K_DEDUP, cfg.dedupMinutes)
            putInt(K_DELAY, cfg.delaySeconds)
            putInt(K_SUBS_ID, cfg.subsId)
            putInt(K_LOG_LIMIT, cfg.logLimit)
            putBoolean(K_CODE_ONLY, cfg.codeOnly)
            putBoolean(K_NET_ENABLED, cfg.netEnabled)
            putString(K_NET_PRESET, cfg.netPreset.key)
            putString(K_NET_URL, cfg.netUrl)
        }.apply()
    }

    // ------------------------------------------------------------ 心跳 / 停止标志
    fun writeHeartbeat(ts: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(K_HEARTBEAT, ts).apply()
    }

    fun readHeartbeat(): Long = prefs.getLong(K_HEARTBEAT, 0L)

    fun isServiceAlive(maxAgeMillis: Long = 15_000L): Boolean {
        val hb = readHeartbeat()
        return hb > 0 && (System.currentTimeMillis() - hb) <= maxAgeMillis
    }

    fun setServiceShouldRun(shouldRun: Boolean) {
        prefs.edit().putBoolean(K_SHOULD_RUN, shouldRun).apply()
    }

    fun shouldServiceRun(): Boolean = prefs.getBoolean(K_SHOULD_RUN, false)

    // ------------------------------------------------------------ 转发日志
    fun appendLog(entry: LogEntry) {
        val limit = loadConfig().logLimit.coerceAtLeast(1)
        val arr = readLogArray()
        arr.put(
            JSONObject().apply {
                put("ts", entry.ts)
                put("level", entry.level)
                put("msg", entry.msg)
                put("from", entry.from)
                put("to", entry.to)
            },
        )
        // 裁剪到 limit 条
        while (arr.length() > limit) {
            arr.remove(0)
        }
        prefs.edit().putString(K_LOG, arr.toString()).apply()
    }

    fun readLogs(): List<LogEntry> {
        val arr = readLogArray()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                LogEntry(
                    ts = o.optString("ts"),
                    level = o.optString("level"),
                    msg = o.optString("msg"),
                    from = o.optString("from"),
                    to = o.optString("to"),
                )
            }.getOrNull()
        }
    }

    fun clearLogs() {
        prefs.edit().remove(K_LOG).apply()
    }

    private fun readLogArray(): JSONArray {
        val raw = prefs.getString(K_LOG, null) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

    companion object {
        private const val PREF_NAME = "sms_forwarder_config"
        private const val K_ENABLED = "enabled"
        private const val K_RECEIVERS = "receivers"
        private const val K_SENDERS = "senders"
        private const val K_SENDER_MODE = "sender_mode"
        private const val K_KEYWORDS = "keywords"
        private const val K_KEYWORD_MODE = "keyword_mode"
        private const val K_CASE_SENSITIVE = "case_sensitive"
        private const val K_EXCLUDE = "exclude_keywords"
        private const val K_TEMPLATE = "template"
        private const val K_MAX_LEN = "max_len"
        private const val K_SPLIT = "split_sms"
        private const val K_DEDUP = "dedup_minutes"
        private const val K_DELAY = "delay_seconds"
        private const val K_SUBS_ID = "subs_id"
        private const val K_LOG_LIMIT = "log_limit"
        private const val K_CODE_ONLY = "code_only"
        private const val K_NET_ENABLED = "net_enabled"
        private const val K_NET_PRESET = "net_preset"
        private const val K_NET_URL = "net_url"
        private const val K_HEARTBEAT = "heartbeat"
        private const val K_SHOULD_RUN = "should_run"
        private const val K_LOG = "logs"

        /** 供 UI 复用：把多行文本框内容转成规则列表。 */
        fun parseList(text: String): List<String> = Rules.parseList(text)
    }
}
