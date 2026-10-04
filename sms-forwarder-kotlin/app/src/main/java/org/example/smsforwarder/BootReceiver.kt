package org.example.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.example.smsforwarder.data.ConfigStore

/**
 * 开机自启：若用户此前开启过转发服务，则开机后自动恢复，
 * 避免每次重启都要手动点「启动服务」。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = ConfigStore(context)
        if (store.shouldServiceRun() && store.isServiceAlive(maxAgeMillis = 1L).not()) {
            // 服务当前不存活且用户希望它运行 → 拉起
            runCatching { ForwardService.start(context) }
        }
    }
}
