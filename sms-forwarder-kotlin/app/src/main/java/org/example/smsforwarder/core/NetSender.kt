package org.example.smsforwarder.core

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 联网推送通道。
 *
 * 安全边界（用户明确要求）：**联网只发提取出来的纯数字验证码**，
 * 绝不带短信正文、发信人号码、时间等任何额外内容。
 * 因此 [build] 只接受一个 code 参数——从签名上就杜绝了误传全文的可能。
 *
 * 用 JDK 自带的 [HttpURLConnection]，不引入 OkHttp 等第三方库，
 * 既减小体积也避免把一堆网络依赖打进包里。
 */
object NetSender {

    /** 一次推送请求。 */
    data class NetRequest(
        val url: String,
        val body: String,
        /** true=application/json；false=表单。 */
        val json: Boolean,
    )

    /** 标题固定，避免把业务信息泄露出去。 */
    private const val TITLE = "验证码"

    /**
     * 按渠道构造请求。纯函数，不碰网络，可在 JVM 单测里直接断言。
     * 参数不合法时返回 null，由调用方记日志。
     */
    fun build(preset: NetPreset, keyOrUrl: String, code: String): NetRequest? {
        val raw = keyOrUrl.trim()
        val safeCode = code.trim()
        if (raw.isEmpty() || safeCode.isEmpty()) return null
        if (!safeCode.all { it.isDigit() }) return null // 只发纯数字

        return when (preset) {
            NetPreset.SERVERCHAN -> NetRequest(
                url = "https://sctapi.ftqq.com/${raw}.send",
                body = "title=${enc(TITLE)}&desp=${enc(safeCode)}",
                json = false,
            )

            NetPreset.BARK -> NetRequest(
                url = "https://api.day.app/$raw",
                body = """{"title":"$TITLE","body":"$safeCode","group":"短信转发"}""",
                json = true,
            )

            NetPreset.PUSHPLUS -> NetRequest(
                url = "https://www.pushplus.plus/send",
                body = """{"token":"$raw","title":"$TITLE","content":"$safeCode","template":"txt"}""",
                json = true,
            )

            NetPreset.CUSTOM -> NetRequest(
                url = raw,
                body = """{"code":"$safeCode"}""",
                json = true,
            )
        }
    }

    private fun enc(s: String): String =
        runCatching { URLEncoder.encode(s, "UTF-8") }.getOrDefault(s)

    /** 执行一次推送。返回 [是否成功, 说明]。必须在 IO 线程调用。 */
    fun send(req: NetRequest): Pair<Boolean, String> = runCatching {
        val conn = (URL(req.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("User-Agent", "sms-forwarder")
            setRequestProperty(
                "Content-Type",
                if (req.json) "application/json; charset=utf-8"
                else "application/x-www-form-urlencoded",
            )
        }
        val code = try {
            conn.outputStream.use { it.write(req.body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
        if (code in 200..299) true to "HTTP $code" else false to "HTTP $code"
    }.getOrElse { false to "${it.javaClass.simpleName}: ${it.message}" }
}
