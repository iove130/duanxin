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
    // 列表类字段一律用换行分隔的字符串存，不用 getStringSet。
    // 原因：getStringSet 底层是 HashSet，没有顺序保证——用户按顺序填的
    // 接收人 / 关键词，重启后顺序会被打乱，文本框里的内容会「跳」。
    // 换行分隔既保序又可读，代价只是几十字节。
    //
    // 这个改动会让老版本用 set 存的数据读不出来，所以配了 migrateSetIfNeeded()：
    // 首次启动时检测到旧格式就转成新格式并落盘。顺序无法还原（HashSet 本来就没有），
    // 但内容一个不丢——对用户来说，「顺序可能变」远好过「配置全没了」。
    private fun loadList(key: String): List<String> =
        prefs.getString(key, null).orEmpty()
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun saveList(builder: SharedPreferences.Editor, key: String, list: List<String>) {
        builder.putString(key, list.joinToString("\n"))
    }

    /**
     * 键名 → 旧版本存放 set 的键名。
     *
     * 关键：新旧 key 必须**改名**（新key = 旧key + "_v2"），不能复用同一个名字。
     * SharedPreferences 同一个 key 只能是一种类型，若新代码对 "receivers" 调
     * getString，而旧代码往 "receivers" 写的是 set，运行时会抛
     * ClassCastException，直接崩在启动路径上——所以新格式必须换 key。
     */
    private val legacySetKeys = mapOf(
        K_RECEIVERS_V2 to K_RECEIVERS,
        K_SENDERS_V2 to K_SENDERS,
        K_KEYWORDS_V2 to K_KEYWORDS,
        K_EXCLUDE_V2 to K_EXCLUDE,
    )

    /**
     * 把老版本用 getStringSet 存的数据迁移成换行分隔字符串。
     *
     * 只在 [K_MIGRATED] 标记不存在时跑一次；跑完就写标记，
     * 之后每次启动都是零开销的空判断。原始 set 保留不删——
     * 万一迁移逻辑有 bug，用户降级回老版本还能读到原数据。
     */
    private fun migrateSetIfNeeded(): Boolean {
        if (prefs.getBoolean(K_MIGRATED, false)) return false

        var changed = false
        prefs.edit().apply {
            for ((newKey, oldKey) in legacySetKeys) {
                // 新格式已经有值了，说明迁移过（或用户已重新填过），不再覆盖
                if (prefs.getString(newKey, null) != null) continue
                val legacy = runCatching { prefs.getStringSet(oldKey, null) }.getOrNull()
                if (legacy.isNullOrEmpty()) continue
                saveList(this, newKey, legacy.filter { it.isNotBlank() })
                changed = true
            }
            putBoolean(K_MIGRATED, true)
        }.apply()
        return changed
    }

    fun loadConfig(): ForwardConfig {
        migrateSetIfNeeded()
        val d = ForwardConfig.DEFAULT
        return ForwardConfig(
            enabled = prefs.getBoolean(K_ENABLED, d.enabled),
            receivers = loadList(K_RECEIVERS_V2),
            senders = loadList(K_SENDERS_V2),
            senderMode = SenderMode.fromKey(prefs.getString(K_SENDER_MODE, d.senderMode.key)),
            keywords = loadList(K_KEYWORDS_V2),
            keywordMode = KeywordMode.fromKey(prefs.getString(K_KEYWORD_MODE, d.keywordMode.key)),
            caseSensitive = prefs.getBoolean(K_CASE_SENSITIVE, d.caseSensitive),
            excludeKeywords = loadList(K_EXCLUDE_V2),
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
            saveList(this, K_RECEIVERS_V2, cfg.receivers)
            saveList(this, K_SENDERS_V2, cfg.senders)
            putString(K_SENDER_MODE, cfg.senderMode.key)
            saveList(this, K_KEYWORDS_V2, cfg.keywords)
            putString(K_KEYWORD_MODE, cfg.keywordMode.key)
            putBoolean(K_CASE_SENSITIVE, cfg.caseSensitive)
            saveList(this, K_EXCLUDE_V2, cfg.excludeKeywords)
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
        // 这里只取 logLimit，直接读 prefs 而不走 loadConfig()：
        // appendLog 处在转发主路径上，每条短信都会调好几次，
        // 不该顺带触发整套配置解析 + 迁移检查。
        val limit = prefs.getInt(K_LOG_LIMIT, ForwardConfig.DEFAULT.logLimit).coerceAtLeast(1)
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

        // 列表字段有两代键名。老键存的是 StringSet（无序），新键存换行分隔字符串（有序）。
        // 同一 SharedPreferences 的 key 只能有一种类型，所以新格式必须换 key；
        // 老键不删，留作降级回滚时的数据备份。
        private const val K_RECEIVERS = "receivers"
        private const val K_SENDERS = "senders"
        private const val K_KEYWORDS = "keywords"
        private const val K_EXCLUDE = "exclude_keywords"
        private const val K_RECEIVERS_V2 = "receivers_v2"
        private const val K_SENDERS_V2 = "senders_v2"
        private const val K_KEYWORDS_V2 = "keywords_v2"
        private const val K_EXCLUDE_V2 = "exclude_keywords_v2"
        private const val K_MIGRATED = "set_to_list_migrated"

        private const val K_SENDER_MODE = "sender_mode"
        private const val K_KEYWORD_MODE = "keyword_mode"
        private const val K_CASE_SENSITIVE = "case_sensitive"
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
