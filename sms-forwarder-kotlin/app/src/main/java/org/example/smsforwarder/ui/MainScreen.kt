package org.example.smsforwarder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.example.smsforwarder.core.KeywordMode
import org.example.smsforwarder.core.LogEntry
import org.example.smsforwarder.core.NetPreset
import org.example.smsforwarder.core.SenderMode
import org.example.smsforwarder.sms.SmsHelper
import org.example.smsforwarder.ui.theme.Accent
import org.example.smsforwarder.ui.theme.Card2Dark
import org.example.smsforwarder.ui.theme.OkDark
import org.example.smsforwarder.ui.theme.SubDark
import org.example.smsforwarder.ui.theme.TextDark

@Composable
fun MainScreen(onRequestPermissions: () -> Unit = {}) {
    val vm: MainViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf<ConfirmReq?>(null) }
    var showTips by remember { mutableStateOf(false) }

    fun show(msg: String) = SmsHelper.toast(ctx, msg)

    val draft = state.draft
    // 每帧可能重组多次，别在 Composable 里反复查 SharedPreferences
    val missingPerms = SmsHelper.missingPermissions(ctx)
    val isXiaomi = vm.isXiaomiRom()
    // 自检清单要跳转到对应卡片，用滚动偏移量做锚点跳转，
    // 否则「去填写」只能弹一句 Toast，用户还以为 App 没反应。
    val scrollState = rememberScrollState()
    // 刻意用普通 map 而不是 snapshot state：它在 layout 阶段被写、
    // 只在协程里读一次，用 snapshot state 会白白触发重组。
    // value 是 Float：LayoutCoordinates.positionInParent() 返回 Float。
    val cardOffsets = remember { HashMap<String, Float>() }
    var pendingScroll by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pendingScroll) {
        val key = pendingScroll ?: return@LaunchedEffect
        // animateScrollTo 收 Int，锚点是 Float，这里取整并夹到非负
        cardOffsets[key]?.let { scrollState.animateScrollTo(it.toInt().coerceAtLeast(0)) }
        pendingScroll = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ---- 顶部标题栏（常驻，不随内容滚动）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Card2Dark)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "短信转发器",
                color = TextDark,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            StatusBadge(state.serviceRunning, if (state.serviceRunning) "运行中" else "未启动")
        }

        // ---- 顶部常驻操作栏
        // 修复：原来「保存配置」在第 7 张卡片底部，改完上面配置要滚到底才能保存，
        // 极易漏保存。现在提到固定位置，任何时候都能一键保存。
        Surface(color = Color(0xFF101615)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(
                        text = if (state.serviceRunning) "重启服务" else "启动服务",
                        modifier = Modifier.weight(1f),
                        color = if (state.serviceRunning) Card2Dark else Accent,
                    ) {
                        if (state.serviceRunning) {
                            confirm = ConfirmReq("重启服务", "会先停止再启动，期间可能漏掉一条短信。") {
                                show(vm.stopService())
                                show(vm.startService())
                            }
                        } else {
                            show(vm.saveAndStart())
                        }
                    }
                    ActionButton(
                        text = "保存配置",
                        modifier = Modifier.weight(1f),
                        color = if (state.dirty) Color(0xFFBA7517) else Card2Dark,
                    ) { show(vm.save()) }
                }
                if (state.dirty) {
                    Text(
                        "有未保存的修改，点「保存配置」生效",
                        color = Color(0xFFFFC46B),
                        fontSize = 11.5.sp,
                    )
                }
            }
        }

        // 滚动主体
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- 1. 开机自检：一眼看清「还差什么」
            // 修复：原来这些信息散落在 7 张卡片里，新用户根本不知道要做什么。
            SectionCard(
                "开机自检",
                "按顺序完成这 4 项，就能正常转发验证码了。",
            ) {
                CheckRow(
                    done = missingPerms.isEmpty(),
                    label = "授予短信权限",
                    detail = if (missingPerms.isEmpty()) {
                        "已全部授予"
                    } else {
                        "还缺：" + SmsHelper.missingShortNames(ctx).joinToString("、")
                    },
                    actionText = "去授权",
                    onAction = {
                        if (missingPerms.isEmpty()) show("权限已全部授予") else onRequestPermissions()
                    },
                )
                CheckRow(
                    done = draft.receivers.any { it.isNotBlank() },
                    label = "填写接收人手机号",
                    detail = "验证码要转到哪台手机",
                    actionText = "去填写",
                    onAction = { pendingScroll = CARD_RECEIVERS },
                )
                CheckRow(
                    done = draft.keywordMode == KeywordMode.AUTO && draft.codeOnly,
                    label = "启用「自动识别验证码」",
                    detail = "一条验证码只发一条、只确认一次，也不会被拆成多条计费",
                    actionText = "一键启用",
                    onAction = {
                        vm.setKeywordMode(KeywordMode.AUTO)
                        vm.setCodeOnly(true)
                        pendingScroll = CARD_KEYWORD
                    },
                )
                CheckRow(
                    done = state.serviceRunning,
                    label = "启动转发服务",
                    detail = state.heartbeatText,
                    actionText = "启动",
                    onAction = { show(vm.saveAndStart()) },
                )
            }

            // ---- 2. 小米专属提示（只在小米机型上出现）
            // BUG 修复：这段说明原先无条件渲染，非小米用户也会看到「澎湃OS」字样。
            if (isXiaomi) {
                SectionCard(
                    "小米手机必读",
                    "澎湃OS / MIUI 有两层针对短信的保护，配置不对就会收不到验证码。",
                ) {
                    NoticeBar(
                        title = "① 验证码可能被系统拦截",
                        body = vm.romHint(),
                        actionText = "去开启通知类短信权限",
                    ) { vm.openPermissionManager() }

                    NoticeBar(
                        title = "② 发送短信时会弹「是否允许发送」",
                        body = "这是系统优化模块的行为，应用代码无法关闭，需要你手动关一次：\n" +
                            "1. 设置 → 我的设备 → 全部参数 → 连点 7 次版本号，进入开发者模式\n" +
                            "2. 设置 → 更多设置 → 开发者选项 → 关闭「启动系统优化」\n" +
                            "（找不到该选项时，点「重置为默认值」多按几次就会显现）\n" +
                            "关掉后即可全自动发送，不再需要每次点确认。",
                        actionText = "打开开发者选项",
                    ) { vm.openDeveloperOptions() }
                }
            }

            // ---- 3. 系统设置
            SectionCard(
                "系统设置",
                "国产 ROM 还需要手动允许自启动与后台运行，否则进程会被杀掉。",
                collapsible = true,
                initiallyExpanded = false,
            ) {
                Text(state.permText, color = SubDark, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("系统设置", Modifier.weight(1f), Card2Dark) { vm.openSettings() }
                    ActionButton("电池优化白名单", Modifier.weight(1f), Card2Dark) { vm.openBatterySettings() }
                }
                ActionButton(
                    text = "短信库自检（看兜底通道是否可用）",
                    color = Card2Dark,
                ) { vm.probeInbox() }
            }

            // ---- 4. 接收人
            SectionCard(
                title = "① 接收人手机号",
                hint = "验证码要转发到哪台手机。支持多个号码，每行一个。",
                modifier = Modifier.onGloballyPositioned {
                    cardOffsets[CARD_RECEIVERS] = it.positionInParent().y
                },
            ) {
                LabeledField(
                    label = "",
                    value = draft.receivers.joinToString("\n"),
                    onValueChange = vm::setReceivers,
                    placeholder = "例如 13800138000",
                    singleLine = false,
                )
                // 实时校验：号码格式错了要立刻说，而不是等到发信失败才察觉
                val bad = draft.receivers.filter { it.isNotBlank() && !it.matches(Regex("^1[3-9]\\d{9}$")) }
                if (bad.isNotEmpty()) {
                    Text(
                        "这些号码看起来不是 11 位手机号：${bad.joinToString("、")}",
                        color = Color(0xFFD96B6B),
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                    )
                }
            }

            // ---- 5. 发信人过滤
            SectionCard(
                "② 发信人过滤（可选）",
                "留空表示接收所有号码。支持通配符：1069* 前缀、*10086 后缀、*95588* 包含。",
                collapsible = true,
                initiallyExpanded = false,
            ) {
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

            // ---- 6. 筛选方式
            SectionCard(
                title = "③ 筛选方式",
                hint = "推荐用「自动识别验证码」：无需填写关键词，短信到达时自动提取纯数字验证码转发，且不会被拆成多条计费。",
                modifier = Modifier.onGloballyPositioned {
                    cardOffsets[CARD_KEYWORD] = it.positionInParent().y
                },
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
                    ToggleRow(
                        label = "只转发纯数字验证码",
                        checked = draft.codeOnly,
                        hint = "关闭后转发完整原文（含发信人与时间，会超 70 字按多条计费）",
                    ) { vm.setCodeOnly(it) }
                } else {
                    Text(
                        "任一=命中任意一个词即转发；全部=所有词同时出现；正则=每行一个正则表达式。",
                        color = SubDark,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
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
                ToggleRow("区分大小写", draft.caseSensitive) { vm.setCaseSensitive(it) }
            }

            // ---- 7. 转发内容
            SectionCard(
                "④ 转发内容",
                "可用变量：{from} 发信人、{time} 时间、{sim} 卡槽、{body} 原文。",
                collapsible = true,
                initiallyExpanded = false,
            ) {
                LabeledField(
                    label = "模板",
                    value = draft.template,
                    onValueChange = vm::setTemplate,
                    singleLine = false,
                )
                NumberField(
                    label = "单条最大字数",
                    value = draft.maxLen,
                    onValueChange = vm::setMaxLen,
                    suffix = "字",
                    minValue = 1,
                    maxValue = 5000,
                )
                ToggleRow("超长自动拆分多条", draft.splitSms) { vm.setSplitSms(it) }
                NumberField(
                    label = "去重窗口",
                    value = draft.dedupMinutes,
                    onValueChange = vm::setDedup,
                    suffix = "分钟（0=不去重）",
                    maxValue = 1440,
                )
                NumberField(
                    label = "转发延迟",
                    value = draft.delaySeconds,
                    onValueChange = vm::setDelay,
                    suffix = "秒",
                    maxValue = 600,
                )
                // SIM 卡选择
                Text("发送用 SIM 卡", color = SubDark, fontSize = 13.sp)
                ChipGroup(
                    options = buildList {
                        add("系统默认")
                        state.sims.forEach { add("卡${it.slot + 1} ${it.carrier}") }
                    },
                    selected = state.sims.firstOrNull { it.subsId == draft.subsId }
                        ?.let { "卡${it.slot + 1} ${it.carrier}" } ?: "系统默认",
                    onSelect = { label ->
                        val hit = state.sims.firstOrNull {
                            label == "卡${it.slot + 1} ${it.carrier}"
                        }
                        vm.setSubsId(hit?.subsId ?: -1)
                    },
                )
            }

            // ---- 8. 联网推送（可选）
            SectionCard(
                "联网推送（可选）",
                "安全边界：联网只会 POST 出去提取到的纯数字验证码，短信正文、发信人、时间一律不外传。关闭时应用不产生任何网络流量。",
                collapsible = true,
                initiallyExpanded = false,
            ) {
                ToggleRow(
                    label = "启用联网推送",
                    checked = draft.netEnabled,
                    hint = "短信来了立刻推送到手机通知栏，不占用短信通道、不计费",
                ) { vm.setNetEnabled(it) }
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

            // ---- 9. 运行控制与危险操作
            SectionCard("运行控制") {
                ToggleRow(
                    label = "启用短信转发",
                    checked = draft.enabled,
                    hint = "关掉后服务仍在运行，但不会真的发出短信",
                ) { vm.setEnabled(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("测试转发", Modifier.weight(1f), Card2Dark) { show(vm.testForward()) }
                    GhostButton("停止服务", Modifier.weight(1f), Color(0xFFD96B6B)) {
                        confirm = ConfirmReq(
                            "停止服务",
                            "停止后将收不到任何短信，也不会再自动转发。",
                        ) { show(vm.stopService()) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("清空日志", Modifier.weight(1f)) {
                        confirm = ConfirmReq(
                            "清空日志",
                            "所有转发记录会被删除，且无法恢复。排障时先看清再清。",
                        ) { show(vm.clearLogs()) }
                    }
                    GhostButton("使用说明", Modifier.weight(1f)) { showTips = true }
                }
            }

            // ---- 10. 日志
            SectionCard("转发日志", "最近 40 条，用于确认「到底转发没转发」。") {
                if (state.logs.isEmpty()) {
                    EmptyState(
                        icon = "📭",
                        title = "还没有任何记录",
                        detail = "收到短信后这里会显示命中、转发、跳过的原因",
                    )
                } else {
                    LogList(state.logs)
                }
            }

            Box(modifier = Modifier.height(20.dp))
        }
    }

    // ---- 危险操作二次确认
    confirm?.let { req ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(req.title, color = TextDark) },
            text = { Text(req.body, color = SubDark, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    req.onConfirm()
                }) {
                    Text("确定", color = Color(0xFFD96B6B), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) {
                    Text("取消", color = SubDark)
                }
            },
            containerColor = Color(0xFF1D2422),
        )
    }

    // ---- 使用说明：Toast 装不下这么多行，必须用可滚动对话框
    if (showTips) {
        AlertDialog(
            onDismissRequest = { showTips = false },
            title = { Text("使用说明", color = TextDark) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(TIPS, color = SubDark, fontSize = 12.5.sp, lineHeight = 19.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { showTips = false }) {
                    Text("知道了", color = Accent, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Color(0xFF1D2422),
        )
    }
}

/** 一次待确认的危险操作。 */
private class ConfirmReq(
    val title: String,
    val body: String,
    val onConfirm: () -> Unit,
)

/** 「开机自检」跳转锚点，避免用文案引导用户自己找。 */
private const val CARD_RECEIVERS = "receivers"
private const val CARD_KEYWORD = "keyword"

/** 日志列表：左侧竖线用颜色区分级别，比纯文本符号更容易扫读。 */
@Composable
private fun LogList(logs: List<LogEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        logs.forEach { entry ->
            // 四个级别必须穷举。漏掉 warn 会让它 fall through 到 else 显示成
            // 绿色，看着像一切正常——而它恰恰是「兜底通道没启用」这类关键告警。
            val dot = when (entry.level) {
                "error" -> Color(0xFFD96B6B)
                "warn" -> Color(0xFFE0A030)
                "skip" -> SubDark.copy(alpha = 0.7f)
                else -> OkDark
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    Modifier
                        .padding(top = 6.dp, end = 8.dp)
                        .width(3.dp)
                        .heightIn(min = 14.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(dot),
                )
                Text(
                    text = "${entry.ts.drop(5)}  ${entry.msg}",
                    color = if (entry.level == "skip") SubDark else TextDark,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }
    }
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
