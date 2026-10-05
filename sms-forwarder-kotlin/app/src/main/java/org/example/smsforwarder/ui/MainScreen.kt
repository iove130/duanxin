package org.example.smsforwarder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.example.smsforwarder.core.KeywordMode
import org.example.smsforwarder.core.NetPreset
import org.example.smsforwarder.core.SenderMode
import org.example.smsforwarder.sms.SmsHelper
import org.example.smsforwarder.ui.theme.Accent
import org.example.smsforwarder.ui.theme.Card2Dark
import org.example.smsforwarder.ui.theme.DangerDark
import org.example.smsforwarder.ui.theme.OkDark
import org.example.smsforwarder.ui.theme.SubDark
import org.example.smsforwarder.ui.theme.TextDark

@Composable
fun MainScreen(onRequestPermissions: () -> Unit = {}) {
    val vm: MainViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var toast by remember { mutableStateOf<String?>(null) }

    fun show(msg: String) {
        toast = msg
        SmsHelper.toast(ctx, msg)
    }

    fun toastThenClear() {
        // 简易提示条，几秒后自动消失
        toast?.let {
            toast = null
        }
    }

    val draft = state.draft

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 顶部标题栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Card2Dark)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "短信转发器",
                color = TextDark,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .background(
                        if (state.serviceRunning) OkDark else SubDark,
                        RoundedCornerShape(50),
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = if (state.serviceRunning) "运行中" else "未启动",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // 滚动主体
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- 1. 运行状态
            SectionCard("运行状态", "转发服务必须保持前台运行，才能在中后台监听到短信。") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(
                        text = "启动服务",
                        modifier = Modifier.weight(1f),
                        color = if (state.serviceRunning) Card2Dark else Accent,
                    ) { show(vm.startService()) }
                    ActionButton(
                        text = "停止服务",
                        modifier = Modifier.weight(1f),
                        color = if (state.serviceRunning) DangerDark else Card2Dark,
                    ) { show(vm.stopService()) }
                }
                Text(state.heartbeatText, color = SubDark, fontSize = 12.sp)
            }

            // ---- 2. 权限
            SectionCard("权限", "首次使用请点击授权；部分国产 ROM 还需手动允许自启动与后台运行。") {
                Text(state.permText, color = SubDark, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(
                        text = "申请权限",
                        modifier = Modifier.weight(1f),
                        color = Accent,
                    ) {
                        if (SmsHelper.missingPermissions(ctx).isEmpty()) {
                            show("权限已全部授予")
                        } else {
                            onRequestPermissions()
                            show("请在弹窗中允许短信权限")
                        }
                        vm.refresh()
                    }
                    ActionButton(
                        text = "系统设置",
                        modifier = Modifier.weight(1f),
                        color = Card2Dark,
                    ) { vm.openSettings() }
                }
                ActionButton(
                    text = "电池优化白名单",
                    color = Card2Dark,
                ) { vm.openBatterySettings() }

                // 小米系 ROM（MIUI / 澎湃OS）：验证码属于「通知类短信」，可能被系统拦截
                val romHint = vm.romHint()
                if (romHint.isNotEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF3A2A12), RoundedCornerShape(10.dp))
                            .padding(12.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "小米手机（澎湃OS / MIUI）提示",
                                color = Color(0xFFFFC46B),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                romHint,
                                color = Color(0xFFF0D9B0),
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                            )
                            ActionButton(
                                text = "去开启通知类短信权限",
                                color = Color(0xFFBA7517),
                            ) { vm.openPermissionManager() }
                        }
                    }
                }

                // 小米系 ROM 的发送确认弹窗由「系统优化」模块弹出，只能手动关
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2A1A2E), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "发送短信时弹「是否允许发送」确认框",
                            color = Color(0xFFFFC46B),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "这是澎湃OS/MIUI 的系统级保护，应用代码无法关闭，需要你手动关一次：\n" +
                                "1. 设置 → 我的设备 → 全部参数 → 连点 7 次版本号，进入开发者模式\n" +
                                "2. 设置 → 更多设置 → 开发者选项 → 关闭「启动系统优化」" +
                                "（找不到该选项时，点「重置为默认值」多按几次就会显现）\n" +
                                "关掉后本 App 即可全自动发送，不再需要每次点确认。",
                            color = Color(0xFFEBD9F0),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                        ActionButton(
                            text = "打开开发者选项",
                            color = Color(0xFF7A3E9D),
                        ) { vm.openDeveloperOptions() }
                    }
                }

                ActionButton(
                    text = "短信库自检（看兜底通道是否可用）",
                    color = Card2Dark,
                ) { vm.probeInbox() }
            }

            // ---- 3. 接收人
            SectionCard("接收人手机号（目标手机）", "支持多个号码，每行一个或逗号分隔；会用本机短信发出。") {
                LabeledField(
                    label = "",
                    value = draft.receivers.joinToString("\n"),
                    onValueChange = vm::setReceivers,
                    placeholder = "例如 13800138000",
                    singleLine = false,
                )
            }

            // ---- 4. 发信人过滤
            SectionCard("发信人过滤", "留空表示接收所有号码。支持通配符：1069* 前缀、*10086 后缀、*95588* 包含。") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("模式", color = SubDark, fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp))
                    ChipGroup(
                        options = listOf("白名单", "黑名单"),
                        selected = draft.senderMode.label,
                        onSelect = { vm.setSenderMode(SenderMode.fromLabel(it)) },
                    )
                }
                LabeledField(
                    label = "",
                    value = draft.senders.joinToString("\n"),
                    onValueChange = vm::setSenders,
                    placeholder = "例如 10086 / 1069* / 13800138000",
                    singleLine = false,
                )
            }

            // ---- 5. 关键词
            SectionCard(
                "筛选方式",
                "推荐用「自动识别验证码」：无需填写关键词，短信到达时自动提取纯数字验证码转发，且不会被拆成多条计费。",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("模式", color = SubDark, fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp))
                    ChipGroup(
                        options = listOf("自动识别验证码", "任一", "全部", "正则"),
                        selected = draft.keywordMode.label,
                        onSelect = { vm.setKeywordMode(KeywordMode.fromLabel(it)) },
                    )
                }

                if (draft.keywordMode == KeywordMode.AUTO) {
                    ToggleRow("只转发纯数字验证码", draft.codeOnly, vm::setCodeOnly)
                    Text(
                        "关闭后转发完整原文（含发信人与时间，会超 70 字按多条计费）。",
                        color = SubDark, fontSize = 12.sp,
                    )
                } else {
                    Text(
                        "任一=命中任意一个词即转发；全部=所有词同时出现；正则=每行一个正则表达式。",
                        color = SubDark, fontSize = 12.sp,
                    )
                    LabeledField(
                        label = "",
                        value = draft.keywords.joinToString("\n"),
                        onValueChange = vm::setKeywords,
                        placeholder = "例如 验证码 / 交易 / 余额",
                        singleLine = false,
                    )
                }

                LabeledField(
                    label = "屏蔽词（命中则不转发）",
                    value = draft.excludeKeywords.joinToString("\n"),
                    onValueChange = vm::setExclude,
                    placeholder = "例如 退订 / 广告",
                    singleLine = false,
                )
                ToggleRow("区分大小写", draft.caseSensitive, vm::setCaseSensitive)
            }

            // ---- 6. 转发内容
            SectionCard("转发内容", "可用变量：{from} 发信人、{time} 时间、{sim} 卡槽、{body} 原文。") {
                LabeledField(
                    label = "",
                    value = draft.template,
                    onValueChange = vm::setTemplate,
                    singleLine = false,
                )
                LabeledField(
                    label = "单条最大字数",
                    value = draft.maxLen.toString(),
                    onValueChange = vm::setMaxLen,
                    numeric = true,
                )
                ToggleRow("超长自动拆分多条", draft.splitSms, vm::setSplitSms)
                LabeledField(
                    label = "去重窗口（分钟，0=不去重）",
                    value = draft.dedupMinutes.toString(),
                    onValueChange = vm::setDedup,
                    numeric = true,
                )
                LabeledField(
                    label = "转发延迟（秒）",
                    value = draft.delaySeconds.toString(),
                    onValueChange = vm::setDelay,
                    numeric = true,
                )
                // SIM 卡选择
                Text("发送用 SIM 卡", color = SubDark, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val simOptions = buildList {
                        add(-1 to "系统默认")
                        state.sims.forEach { add(it.subsId to "卡${it.slot + 1} ${it.carrier}") }
                    }
                    simOptions.forEach { (sid, label) ->
                        val isOn = draft.subsId == sid
                        Box(
                            modifier = Modifier
                                .background(if (isOn) Accent else Card2Dark, RoundedCornerShape(8.dp))
                                .clickable { vm.setSubsId(sid) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = label,
                                color = if (isOn) Color.White else TextDark,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            // ---- 6.5 联网推送（可选）
            SectionCard(
                "联网推送（可选）",
                "安全边界：联网只会 POST 出去提取到的纯数字验证码，短信正文、发信人、时间一律不外传。关闭时应用不产生任何网络流量。",
            ) {
                ToggleRow("启用联网推送", draft.netEnabled, vm::setNetEnabled)
                Text("渠道", color = SubDark, fontSize = 13.sp)
                ChipGroup(
                    options = NetPreset.entries.map { it.label },
                    selected = draft.netPreset.label,
                    onSelect = { vm.setNetPreset(NetPreset.fromLabel(it)) },
                )
                LabeledField(
                    label = "",
                    value = draft.netUrl,
                    onValueChange = vm::setNetUrl,
                    placeholder = draft.netPreset.hint,
                    singleLine = true,
                )
                ActionButton(
                    text = "发送测试推送（测试码 000000）",
                    color = Card2Dark,
                ) { vm.testNetPush() }
            }

            // ---- 7. 总开关 + 操作
            SectionCard("总开关") {
                ToggleRow("启用短信转发", draft.enabled, vm::setEnabled)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("保存配置", Modifier.weight(1f), Accent) { show(vm.save()) }
                    ActionButton("测试转发", Modifier.weight(1f), Card2Dark) { show(vm.testForward()) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("清空日志", Modifier.weight(1f), Card2Dark) { show(vm.clearLogs()) }
                    ActionButton("保活说明", Modifier.weight(1f), Card2Dark) { show(TIPS) }
                }
            }

            // ---- 8. 日志
            SectionCard("转发日志", "最近 40 条。success=已转发 / skip=被规则拦截 / error=失败。") {
                if (state.logs.isEmpty()) {
                    Text("暂无记录", color = SubDark, fontSize = 12.sp)
                } else {
                    state.logs.forEach { entry ->
                        val color = when (entry.level) {
                            "error" -> Color(0xFFD96B6B)
                            "skip" -> SubDark
                            else -> TextDark
                        }
                        val mark = when (entry.level) {
                            "error" -> "✕"
                            "skip" -> "○"
                            else -> "✓"
                        }
                        Text(
                            text = "[${entry.ts.drop(5)}] $mark ${entry.msg}",
                            color = color,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }
            }

            Box(modifier = Modifier.height(20.dp))
        }
    }

    toastThenClear()
}

private val TIPS = """Android 8 以后第三方应用无法靠静态注册接收短信，本 App 采用「前台服务 + 动态注册」的方式，因此必须保证进程存活：
1. 在系统设置里把本应用加入「自启动 / 后台运行」白名单；
2. 关闭本应用的电池优化（省电策略设为「无限制」）；
3. 多任务界面把本应用卡片下拉锁定；
4. 部分 ROM（MIUI / ColorOS / EMUI）需额外打开「通知栏常驻」；
5. 小米手机（澎湃OS / MIUI）：验证码属于「通知类短信」，系统可能不广播给第三方应用。本应用已启用「短信库兜底通道」，一般无需额外设置；仍收不到时再在「设置 → 应用设置 → 应用管理 → 短信转发器 → 权限管理」允许「通知类短信」；
6. 转发会占用本机短信通道，运营商可能计费，请自行确认套餐；
7. 发送短信时弹「是否允许发送」确认框：这是澎湃OS/MIUI 系统优化模块的行为，
   应用无法关闭，需在「开发者选项」里关闭「启动系统优化」；
8. 建议开启「自动识别验证码 + 只转发纯数字」：一条验证码只发一条、
   只需确认一次，也不会因超 70 字被拆成多条计费；
9. 联网推送默认关闭。开启后也只会把「提取到的纯数字验证码」POST 出去，
   短信正文 / 发信人 / 时间不会离开本机；不开启则不产生任何网络流量。其余数据仅存本机，免登录，卸载即丢失。"""
