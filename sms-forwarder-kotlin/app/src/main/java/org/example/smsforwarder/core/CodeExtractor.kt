package org.example.smsforwarder.core

/**
 * 验证码识别与提取。
 *
 * 纯 Kotlin 实现，不依赖 Android API，可在 JVM 单元测试里覆盖。
 *
 * 识别流程：
 * 1. **触发词**——正文出现「验证码 / 校验码 / 动态码…」等才认为是验证码短信，
 *    避免把普通通知短信里的数字误当成验证码。
 * 2. **提取**——优先取「触发词后紧跟的数字」；取不到则退回「正文中最长的 4~8 位数字段」。
 * 3. **排除干扰**——手机号、金额、时间一律不算验证码。
 *    （注意：全同数字如 8888 / 0000 在某些服务里确实是验证码，不能一刀切排除。）
 */
object CodeExtractor {

    private val TRIGGERS = listOf(
        "验证码", "校验码", "验 证 码", "动态码", "确认码", "短信码",
        "一次性密码", "动态密码", "口令",
        "verification code", "verify code", "security code", "one-time", "otp", "passcode",
    )

    private val NUM_RE = Regex("\\d+")

    /** 触发词之后允许出现的分隔符，提取前先跳过。 */
    private val SEPARATORS = "：:是为到 -—【】[]()（）\t"

    /** 数字段紧跟这些单位时是时间，不是验证码。 */
    private val TIME_UNITS = listOf("年", "月", "日", "号", "时", "分", "秒", "点")

    /** 数字段紧跟这些单位时是金额，不是验证码。 */
    private val MONEY_UNITS = listOf("元", "块钱", "人民币")

    /** 数字段前面是这些符号时是金额，不是验证码。 */
    private val MONEY_PREFIX = listOf("¥", "￥", "$", "RMB")

    /** 提取验证码。返回 [是否成功, 验证码或空串]。 */
    fun extract(body: String?): Pair<Boolean, String> {
        val text = body.orEmpty()
        if (text.isBlank()) return false to ""
        if (TRIGGERS.none { text.contains(it, ignoreCase = true) }) return false to ""

        val code = extractAfterTrigger(text) ?: extractLongestCandidate(text)
        if (code == null || !isPlausibleCode(code)) return false to ""
        return true to code
    }

    /** 取触发词之后最近的有效数字段。 */
    private fun extractAfterTrigger(text: String): String? {
        for (trigger in TRIGGERS) {
            var idx = text.indexOf(trigger, ignoreCase = true)
            while (idx >= 0) {
                val tail = text.substring(idx + trigger.length)
                val cleaned = tail.trimStart { it in SEPARATORS || it.isWhitespace() }
                val num = NUM_RE.find(cleaned)?.value
                if (num != null && isPlausibleCode(num) && !isNoise(text, num)) {
                    return num
                }
                // 同一个触发词可能重复出现（如「您的验证码是123456，验证码有效期…」）
                idx = text.indexOf(trigger, idx + 1, ignoreCase = true)
            }
        }
        return null
    }

    /** 退回策略：取正文中最长的有效数字段。 */
    private fun extractLongestCandidate(text: String): String? =
        NUM_RE.findAll(text)
            .map { it.value }
            .filter { isPlausibleCode(it) && !isNoise(text, it) }
            .maxByOrNull { it.length }

    /** 形态上是否像一个验证码。 */
    private fun isPlausibleCode(num: String): Boolean {
        if (num.length !in 4..8) return false
        // 注意：不能排除「全同数字」——8888 / 0000 在部分服务里确实是验证码。
        // 真正该排除的是长度异常的（如 1111111111）。
        return true
    }

    /** 语义上是否是干扰数字（手机号 / 金额 / 时间）。 */
    private fun isNoise(text: String, num: String): Boolean {
        // 11 位且以 1 开头 —— 典型手机号
        if (num.length == 11 && num.startsWith("1")) return true

        val idx = text.indexOf(num)
        if (idx < 0) return false

        // 中文里单位是跟在数字「后面」的：2026 年 / 5000 元 / 10 分钟
        val after = text.substring(idx + num.length).trimStart()
        if (TIME_UNITS.any { after.startsWith(it) }) return true
        if (MONEY_UNITS.any { after.startsWith(it) }) return true

        // 货币符号是写在数字「前面」的：¥5000 / ￥8888
        val before = text.substring(0, idx).trimEnd()
        if (MONEY_PREFIX.any { before.endsWith(it) }) return true
        return false
    }
}
