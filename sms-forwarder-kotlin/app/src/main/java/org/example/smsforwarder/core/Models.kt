package org.example.smsforwarder.core

enum class SenderMode(val key: String, val label: String) {
    ALLOW("allow", "白名单"),
    BLOCK("block", "黑名单");

    companion object {
        fun fromKey(key: String?): SenderMode =
            entries.firstOrNull { it.key == key } ?: ALLOW

        fun fromLabel(label: String): SenderMode =
            entries.firstOrNull { it.label == label } ?: ALLOW
    }
}

enum class KeywordMode(val key: String, val label: String) {
    ANY("any", "任一"),
    ALL("all", "全部"),
    REGEX("regex", "正则"),
    /** 自动识别验证码：无需手填关键词，命中即提取纯数字码转发。 */
    AUTO("auto", "自动识别验证码");

    companion object {
        fun fromKey(key: String?): KeywordMode =
            entries.firstOrNull { it.key == key } ?: ANY

        fun fromLabel(label: String): KeywordMode =
            entries.firstOrNull { it.label == label } ?: ANY
    }
}

/** 一条待转发的短信（已解析）。 */
data class SmsMessage(
    val from: String,
    val body: String,
    val time: String,
    val sim: String,
    /** 短信原始时间戳（毫秒），仅用于去重与增量扫描，不参与渲染。 */
    val rawDate: Long = 0L,
)

/** 联网推送渠道。 */
enum class NetPreset(val key: String, val label: String, val hint: String) {
    SERVERCHAN("serverchan", "Server酱", "填 SendKey，如 SCT123456xxxx"),
    BARK("bark", "Bark", "填 Bark key（api.day.app 的那一段）"),
    PUSHPLUS("pushplus", "PushPlus", "填 token"),
    CUSTOM("custom", "自定义", "填完整 URL，POST JSON {\"code\":\"123456\"}");

    companion object {
        fun fromKey(key: String?): NetPreset =
            entries.firstOrNull { it.key == key } ?: CUSTOM

        fun fromLabel(label: String): NetPreset =
            entries.firstOrNull { it.label == label } ?: CUSTOM
    }
}

/** 转发配置的不可变快照。 */
data class ForwardConfig(
    val enabled: Boolean = true,
    val receivers: List<String> = emptyList(),
    val senders: List<String> = emptyList(),
    val senderMode: SenderMode = SenderMode.ALLOW,
    val keywords: List<String> = emptyList(),
    val keywordMode: KeywordMode = KeywordMode.ANY,
    val caseSensitive: Boolean = false,
    val excludeKeywords: List<String> = emptyList(),
    val template: String = "[转发] 来自 {from}\n时间 {time}\n\n{body}",
    val maxLen: Int = 500,
    val splitSms: Boolean = true,
    val dedupMinutes: Int = 5,
    val delaySeconds: Int = 0,
    val subsId: Int = -1,
    val logLimit: Int = 300,
    /** AUTO 模式下：只转发提取出的纯数字验证码，不带发信人/时间等前缀。 */
    val codeOnly: Boolean = true,

    // ------------------------------------------------ 联网推送（可选）
    /** 是否启用联网推送。关闭时应用不产生任何网络流量。 */
    val netEnabled: Boolean = false,
    val netPreset: NetPreset = NetPreset.CUSTOM,
    /** Server酱/Bark/PushPlus 填 key 或 token；自定义填完整 URL。 */
    val netUrl: String = "",
) {
    companion object {
        val DEFAULT = ForwardConfig()
    }
}

/** 一条转发日志。 */
data class LogEntry(
    val ts: String,
    val level: String,
    val msg: String,
    val from: String = "",
    val to: String = "",
)
