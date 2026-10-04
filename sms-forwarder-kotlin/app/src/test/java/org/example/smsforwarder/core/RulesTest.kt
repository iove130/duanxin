package org.example.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规则引擎单元测试，对应 Python 版 tests/test_rules.py。 */
class RulesTest {

    private fun msg(from: String, body: String) = SmsMessage(from, body, "2026-01-01 10:00", "卡1")

    // ---------------------------------------------------------- 号码匹配
    @Test
    fun exactMatch() {
        assertTrue(Rules.matchNumber("13800138000", "13800138000"))
    }

    @Test
    fun countryCodeVariants() {
        assertTrue(Rules.matchNumber("+8613800138000", "13800138000"))
        assertTrue(Rules.matchNumber("008613800138000", "13800138000"))
        assertTrue(Rules.matchNumber("8613800138000", "13800138000"))
    }

    @Test
    fun prefixWildcard() {
        assertTrue(Rules.matchNumber("1069020099123", "1069*"))
        assertFalse(Rules.matchNumber("13800138000", "1069*"))
    }

    @Test
    fun suffixWildcard() {
        assertTrue(Rules.matchNumber("9558812345", "*12345"))
        assertTrue(Rules.matchNumber("+869558812345", "*12345"))
    }

    @Test
    fun containsWildcard() {
        assertTrue(Rules.matchNumber("95588", "*95588*"))
    }

    @Test
    fun senderAllowList() {
        val senders = listOf("10086", "1069*")
        assertTrue(Rules.senderAllowed("10086", senders, SenderMode.ALLOW).first)
        assertFalse(Rules.senderAllowed("13800138000", senders, SenderMode.ALLOW).first)
    }

    @Test
    fun emptySenderListMeansAll() {
        val (ok, reason) = Rules.senderAllowed("13800138000", emptyList(), SenderMode.ALLOW)
        assertTrue(ok)
        assertTrue(reason.contains("未设置"))
    }

    @Test
    fun senderBlockList() {
        assertFalse(Rules.senderAllowed("10086", listOf("10086"), SenderMode.BLOCK).first)
        assertTrue(Rules.senderAllowed("13800138000", listOf("10086"), SenderMode.BLOCK).first)
    }

    // ---------------------------------------------------------- 关键词匹配
    @Test
    fun emptyKeywordsPassthrough() {
        assertTrue(Rules.keywordHit("随便一句话", emptyList(), KeywordMode.ANY, false).first)
    }

    @Test
    fun anyKeyword() {
        val kw = listOf("验证码", "余额")
        assertTrue(Rules.keywordHit("您的验证码是 1234", kw, KeywordMode.ANY, false).first)
        assertFalse(Rules.keywordHit("你好呀", kw, KeywordMode.ANY, false).first)
    }

    @Test
    fun allKeywords() {
        val kw = listOf("验证码", "登录")
        assertTrue(Rules.keywordHit("验证码用于登录", kw, KeywordMode.ALL, false).first)
        assertFalse(Rules.keywordHit("验证码到手", kw, KeywordMode.ALL, false).first)
    }

    @Test
    fun caseSensitivity() {
        assertTrue(Rules.keywordHit("Auth Code", listOf("auth"), KeywordMode.ANY, false).first)
        assertFalse(Rules.keywordHit("Auth Code", listOf("auth"), KeywordMode.ANY, true).first)
    }

    @Test
    fun regexKeyword() {
        assertTrue(
            Rules.keywordHit("订单号 A2023100301", listOf("""A\d{10}"""), KeywordMode.REGEX, false).first,
        )
        assertFalse(
            Rules.keywordHit("订单号 X1", listOf("""A\d{10}"""), KeywordMode.REGEX, false).first,
        )
    }

    @Test
    fun excludeWords() {
        val (blocked, word) = Rules.excludeHit("验证码 1234 回T退订", listOf("退订"), false)
        assertTrue(blocked)
        assertEquals("退订", word)
    }

    // ---------------------------------------------------------- 综合判断
    @Test
    fun shouldForwardIntegration() {
        val cfg = ForwardConfig(
            enabled = true,
            senders = listOf("1069*"),
            senderMode = SenderMode.ALLOW,
            keywords = listOf("验证码"),
            keywordMode = KeywordMode.ANY,
            excludeKeywords = listOf("退订"),
        )
        val (ok1, reason1, word1) = Rules.shouldForward(
            msg("10690300123", "您的验证码是 8821"), cfg,
        )
        assertTrue(reason1, ok1)
        assertEquals("验证码", word1)

        val (ok2, reason2) = Rules.shouldForward(msg("13800138000", "您的验证码是 8821"), cfg)
        assertFalse(ok2)
        assertTrue(reason2.contains("白名单"))

        val (ok3, reason3) = Rules.shouldForward(msg("10690300123", "验证码 8821 退订回T"), cfg)
        assertFalse(ok3)
        assertTrue(reason3.contains("屏蔽词"))
    }

    @Test
    fun disabledSwitch() {
        val cfg = ForwardConfig(enabled = false)
        val (ok, reason) = Rules.shouldForward(msg("1", "2"), cfg)
        assertFalse(ok)
        assertTrue(reason.contains("关闭"))
    }

    // ---------------------------------------------------------- 渲染与拆分
    @Test
    fun render() {
        val tpl = "[转发] {from} {time} {body}"
        val out = Rules.render(tpl, msg("10086", "余额 10 元"))
        assertEquals("[转发] 10086 2026-01-01 10:00 余额 10 元", out)
    }

    @Test
    fun renderUnknownVarKeepsText() {
        val out = Rules.render("{bad} 出问题", msg("x", "y"))
        assertTrue(out.isNotEmpty())
    }

    @Test
    fun splitDisabled() {
        assertEquals(1, Rules.splitText("a".repeat(1000), 500, false).size)
    }

    @Test
    fun splitEnabled() {
        val parts = Rules.splitText("a".repeat(1200), 500, true)
        assertEquals(3, parts.size)
        assertTrue(parts[0].startsWith("(1/3)"))
        assertEquals("(1/3)".length + 500, parts[0].length)
    }

    @Test
    fun splitShort() {
        assertEquals(listOf("短"), Rules.splitText("短", 500, true))
    }

    // ---------------------------------------------------------- 去重
    @Test
    fun dedupWindow() {
        val d = Deduplicator(5)
        val now = 1_000_000L
        assertFalse(d.isDuplicate("10086", "验证码 1", now))
        assertTrue(d.isDuplicate("10086", "验证码 1", now + 1_000))
        assertFalse(d.isDuplicate("10086", "验证码 1", now + 301_000))
    }

    @Test
    fun dedupZeroWindow() {
        val d = Deduplicator(0)
        val now = 1_000_000L
        assertFalse(d.isDuplicate("10086", "x", now))
        assertFalse(d.isDuplicate("10086", "x", now))
    }

    // ---------------------------------------------------------- 解析
    @Test
    fun parseListMixedSeparators() {
        val out = Rules.parseList("10086，1069*;\n95588、abc")
        assertEquals(listOf("10086", "1069*", "95588", "abc"), out)
    }

    @Test
    fun parseListEmpty() {
        assertTrue(Rules.parseList("  \n  ").isEmpty())
    }
}
