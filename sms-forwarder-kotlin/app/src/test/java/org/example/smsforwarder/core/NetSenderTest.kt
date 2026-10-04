package org.example.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NetSender 的请求构造单测（纯 JVM，不发真实网络请求）。 */
class NetSenderTest {

    @Test
    fun serverchanBuildsFormRequest() {
        val req = NetSender.build(NetPreset.SERVERCHAN, "SCTabc123", "123456")
        assertNotNull(req)
        assertEquals("https://sctapi.ftqq.com/SCTabc123.send", req!!.url)
        assertFalse(req.json)
        assertTrue(req.body.contains("desp=123456"))
    }

    @Test
    fun barkBuildsJsonRequest() {
        val req = NetSender.build(NetPreset.BARK, "mykey", "8888")!!
        assertEquals("https://api.day.app/mykey", req.url)
        assertTrue(req.json)
        assertTrue(req.body.contains("\"body\":\"8888\""))
    }

    @Test
    fun pushplusBuildsJsonRequest() {
        val req = NetSender.build(NetPreset.PUSHPLUS, "tok123", "4321")!!
        assertEquals("https://www.pushplus.plus/send", req.url)
        assertTrue(req.json)
        assertTrue(req.body.contains("\"token\":\"tok123\""))
        assertTrue(req.body.contains("\"content\":\"4321\""))
    }

    @Test
    fun customUsesGivenUrl() {
        val req = NetSender.build(NetPreset.CUSTOM, "https://example.com/hook", "5678")!!
        assertEquals("https://example.com/hook", req.url)
        assertTrue(req.json)
        assertEquals("""{"code":"5678"}""", req.body)
    }

    /** 安全边界：只发纯数字验证码，其它一律拒绝。 */
    @Test
    fun rejectNonNumericPayload() {
        assertNull(NetSender.build(NetPreset.CUSTOM, "https://example.com/hook", "验证码是1234"))
        assertNull(NetSender.build(NetPreset.CUSTOM, "https://example.com/hook", "abc"))
        assertNull(NetSender.build(NetPreset.CUSTOM, "https://example.com/hook", ""))
        assertNull(NetSender.build(NetPreset.CUSTOM, "", "123456"))
    }

    /** 泄漏防护：请求体里绝不能出现发信人 / 正文等原文。 */
    @Test
    fun neverLeaksSenderOrBody() {
        val req = NetSender.build(NetPreset.CUSTOM, "https://example.com/hook", "123456")!!
        assertFalse(req.body.contains("13800138000"))
        assertFalse(req.body.contains("您的验证码"))
        assertFalse(req.url.contains("13800138000"))
    }
}
