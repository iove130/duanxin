package org.example.smsforwarder.core

/**
 * 纯 Kotlin 的匹配/转发规则引擎（对应 Python 版的 common/rules.py）。
 *
 * 不依赖任何 Android API，因此可以直接在 JVM 上跑单元测试。
 */
object Rules {

    private val DIGIT_RE = Regex("\\D+")

    fun digitsOnly(value: String?): String = DIGIT_RE.replace(value.orEmpty(), "")

    /** 剥掉 +86 / 0086 前缀，只留 11 位国内号码主体用于比较。 */
    fun stripCountryCode(d: String): String = when {
        d.startsWith("0086") && d.length > 11 -> d.substring(4)
        d.startsWith("86") && d.length > 11 -> d.substring(2)
        else -> d
    }

    /**
     * 发信人号码匹配，支持三种通配写法：
     * - 精确：`13800138000` → 忽略国家码后相等即命中
     * - 前缀：`1069*`      → 1069 开头的通道号
     * - 后缀：`*10086`     → 以 10086 结尾
     * - 包含：`*95588*`    → 任意位置包含
     */
    fun matchNumber(sender: String?, pattern: String?): Boolean {
        val s = stripCountryCode(digitsOnly(sender))
        val p = pattern?.trim().orEmpty()
        if (p.isEmpty() || s.isEmpty()) return false

        val hasHead = p.startsWith("*")
        val hasTail = p.endsWith("*")
        val core = stripCountryCode(digitsOnly(p.trim('*')))
        if (core.isEmpty()) return false

        return when {
            hasHead && hasTail -> s.contains(core)
            hasHead -> s.endsWith(core)
            hasTail -> s.startsWith(core)
            // 精确匹配时允许通道号「归属号+扩展位」这种前后包含关系
            else -> s == core || core.endsWith(s) || s.endsWith(core)
        }
    }

    /** 判断发信人是否放行。返回 [是否放行, 原因]。 */
    fun senderAllowed(sender: String?, senders: List<String>, mode: SenderMode): Pair<Boolean, String> {
        val rules = senders.filter { it.isNotBlank() }
        if (rules.isEmpty()) return true to "未设置发信人过滤"

        val hit = rules.firstOrNull { matchNumber(sender, it) }
        return if (mode == SenderMode.BLOCK) {
            if (hit != null) false to "发信人在黑名单($hit)" else true to "非黑名单号码"
        } else {
            if (hit != null) true to "发信人在白名单($hit)" else false to "发信人不在白名单"
        }
    }

    private fun prepare(text: String?, caseSensitive: Boolean): String {
        val s = text.orEmpty()
        return if (caseSensitive) s else s.lowercase()
    }

    /** 关键词是否命中。返回 [是否命中, 命中的词]。关键词为空表示全部转发。 */
    fun keywordHit(
        body: String?,
        keywords: List<String>,
        mode: KeywordMode,
        caseSensitive: Boolean,
    ): Pair<Boolean, String> {
        // AUTO：自动识别验证码，无需用户配置关键词
        if (mode == KeywordMode.AUTO) {
            val (ok, code) = CodeExtractor.extract(body)
            return if (ok) true to code else false to ""
        }

        val words = keywords.map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return true to ""

        val text = prepare(body, caseSensitive)
        return when (mode) {
            KeywordMode.REGEX -> {
                val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                for (w in words) {
                    val hit = runCatching { Regex(w, options).containsMatchIn(body.orEmpty()) }
                        .getOrDefault(false)
                    if (hit) return true to w
                }
                false to ""
            }

            KeywordMode.ALL -> {
                for (w in words) {
                    if (!text.contains(prepare(w, caseSensitive))) return false to ""
                }
                true to words.joinToString(",")
            }

            KeywordMode.ANY -> {
                for (w in words) {
                    if (text.contains(prepare(w, caseSensitive))) return true to w
                }
                false to ""
            }

            // AUTO 已在方法开头单独处理，这里兜底保证 when 穷尽
            KeywordMode.AUTO -> false to ""
        }
    }

    /** 屏蔽词命中判断。词条可用 `/正则/` 包裹以启用正则。 */
    fun excludeHit(body: String?, excludes: List<String>, caseSensitive: Boolean): Pair<Boolean, String> {
        val text = prepare(body, caseSensitive)
        for (raw in excludes) {
            val w = raw.trim()
            if (w.isEmpty()) continue
            if (matchExcludeWord(w, text, caseSensitive)) return true to w
        }
        return false to ""
    }

    private fun matchExcludeWord(w: String, text: String, caseSensitive: Boolean): Boolean {
        return if (w.length > 2 && w.startsWith("/") && w.endsWith("/")) {
            val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            runCatching { Regex(w.substring(1, w.length - 1), options).containsMatchIn(text) }
                .getOrDefault(false)
        } else {
            text.contains(prepare(w, caseSensitive))
        }
    }

    /** 综合判断是否转发。返回三元组 [是否转发, 原因, 命中词]。 */
    fun shouldForward(msg: SmsMessage, cfg: ForwardConfig): Triple<Boolean, String, String> {
        if (!cfg.enabled) return Triple(false, "转发总开关关闭", "")

        val (allowed, reason) = senderAllowed(msg.from, cfg.senders, cfg.senderMode)
        if (!allowed) return Triple(false, reason, "")

        val (hit, word) = keywordHit(msg.body, cfg.keywords, cfg.keywordMode, cfg.caseSensitive)
        if (!hit) return Triple(false, "未命中关键词", "")

        val (blocked, bad) = excludeHit(msg.body, cfg.excludeKeywords, cfg.caseSensitive)
        if (blocked) return Triple(false, "命中屏蔽词($bad)", word)

        return Triple(true, reason, word)
    }

    /** 按模板渲染待转发的正文。可用变量：{from} {time} {body} {sim} {slot} {raw}。 */
    fun render(template: String, msg: SmsMessage): String {
        return template
            .replace("{from}", msg.from)
            .replace("{time}", msg.time)
            .replace("{body}", msg.body)
            .replace("{sim}", msg.sim)
            .replace("{slot}", msg.sim)
            .replace("{raw}", msg.body)
    }

    /** 把超长正文切成多条。maxLen <= 0 或 doSplit=false 时不切。 */
    fun splitText(text: String, maxLen: Int, doSplit: Boolean): List<String> {
        if (!doSplit || maxLen <= 0 || text.length <= maxLen) {
            return if (text.isEmpty()) emptyList() else listOf(text)
        }
        val totalPages = (text.length + maxLen - 1) / maxLen
        return (0 until totalPages).map { i ->
            val from = i * maxLen
            val to = minOf(text.length, from + maxLen)
            "(${i + 1}/$totalPages)" + text.substring(from, to)
        }
    }

    /** 把文本框按逗号/分号/换行拆成列表。 */
    fun parseList(text: String?): List<String> {
        val raw = text.orEmpty()
            .replace("，", ",")
            .replace("；", ";")
            .replace("、", ",")
        return raw.split(Regex("[,;\n\r]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
