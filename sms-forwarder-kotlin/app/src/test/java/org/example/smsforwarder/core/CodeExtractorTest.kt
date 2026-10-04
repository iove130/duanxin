package org.example.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证码提取单元测试。 */
class CodeExtractorTest {

    private fun code(body: String) = CodeExtractor.extract(body)

    // ---------------------------------------------------------- 基础形态
    @Test
    fun commonPatterns() {
        assertEquals("123456", code("您的验证码是123456，5分钟内有效").second)
        assertEquals("123456", code("【某某】验证码：123456，请勿泄露").second)
        assertEquals("8888", code("您的动态码为 8888").second)
        assertEquals("4321", code("校验码 4321，请输入以完成验证").second)
    }

    @Test
    fun withColonAndBrackets() {
        assertEquals("567890", code("短信验证码：【567890】").second)
        assertEquals("9988", code("验证码(9988)").second)
    }

    @Test
    fun sixDigitIsMostCommon() {
        val (ok, v) = code("【工商银行】您的验证码是472915，10分钟内有效。")
        assertTrue(ok)
        assertEquals("472915", v)
    }

    // ---------------------------------------------------------- 必须排除的情况
    @Test
    fun rejectNonCodeMessage() {
        // 没有触发词 -> 不应提取
        assertFalse(code("您本期的账单金额为 120.50 元，请及时还款").first)
        assertFalse(code("您的订单 20260101123 已发货").first)
    }

    @Test
    fun rejectPhoneNumber() {
        // 11 位手机号不能被当成验证码
        assertFalse(code("您的验证码已发送至 13800138000，请注意查收").first)
    }

    @Test
    fun rejectAmountAndTime() {
        // 触发词存在，但附近只有金额和时间 -> 提取失败
        assertFalse(code("【账单】验证码已生成，您的验证码将在 2026 年 10 月 1 日生效，金额 5000 元").first)
    }

    @Test
    fun acceptRepeatedDigits() {
        // 8888 / 6666 在部分服务里确实是验证码，不能一刀切排除
        assertEquals("8888", code("您的动态码为 8888").second)
        assertEquals("1111", code("验证码：1111").second)
    }

    @Test
    fun rejectAbnormalLength() {
        // 长度明显异常的号码段不应作为验证码
        assertFalse(code("您的验证码是123456789012").first)
    }

    // ---------------------------------------------------------- 真实场景
    @Test
    fun realWorldSamples() {
        val samples = listOf(
            "【腾讯科技】您的验证码是 739201，15 分钟内有效，请勿泄露给他人。" to "739201",
            "1069000000001 您的验证码为 5623，10分钟内有效" to "5623",
            "【平安银行】手机银行验证码：307941，5分钟内有效，请勿向任何人泄露" to "307941",
            "您的动态密码是 8246，请勿泄露" to "8246",
        )
        for ((body, expect) in samples) {
            assertEquals("提取失败: $body", expect, code(body).second)
        }
    }

    @Test
    fun handleMultipleTriggerWords() {
        // 触发词出现两次时，应取第一个后面的数字
        val (ok, v) = code("您的验证码是123456，验证码10分钟内有效")
        assertTrue(ok)
        assertEquals("123456", v)
    }

    @Test
    fun englishTrigger() {
        assertEquals("246810", code("Your verification code is 246810").second)
    }

    @Test
    fun emptyInput() {
        assertFalse(code(null).first)
        assertFalse(code("").first)
    }
}
