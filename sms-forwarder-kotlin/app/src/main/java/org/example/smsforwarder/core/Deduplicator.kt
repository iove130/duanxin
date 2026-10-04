package org.example.smsforwarder.core

/**
 * 时间窗口去重：同号码 + 同正文，在窗口内只转发一次。
 * 对应 Python 版的 rules.Deduplicator。
 */
class Deduplicator(windowMinutes: Int = 0) {

    var windowMillis: Long = windowMinutes.toLong() * 60_000L
        private set

    private val seen = HashMap<String, Long>()

    fun updateWindow(windowMinutes: Int) {
        windowMillis = windowMinutes.toLong() * 60_000L
    }

    @Synchronized
    fun isDuplicate(sender: String?, body: String?, now: Long = System.currentTimeMillis()): Boolean {
        if (windowMillis <= 0) return false

        val key = "${Rules.digitsOnly(sender)}|${body.orEmpty()}"
        val last = seen[key]
        if (last != null && (now - last) < windowMillis) {
            return true
        }
        seen[key] = now

        // 顺手清理过期项，避免内存无限增长
        if (seen.size > 500) {
            val expired = seen.entries.filter { now - it.value >= windowMillis }
            expired.forEach { seen.remove(it.key) }
        }
        return false
    }
}
