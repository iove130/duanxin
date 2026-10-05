package org.example.smsforwarder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.smsforwarder.ui.theme.Accent
import org.example.smsforwarder.ui.theme.Card2Dark
import org.example.smsforwarder.ui.theme.FieldDark
import org.example.smsforwarder.ui.theme.OkDark
import org.example.smsforwarder.ui.theme.SubDark
import org.example.smsforwarder.ui.theme.TextDark

/** 统一圆角，卡片与按钮共用，避免各处写死不同的值。 */
val RCard = 16.dp
val RField = 10.dp

/**
 * 带标题的圆角卡片。
 *
 * 视觉层级：标题 15sp 粗体 → 提示 12sp 灰色 → 内容。
 * 标题与内容之间用 spacing 拉开，卡片本身不再额外加粗体。
 */
@Composable
fun SectionCard(
    title: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
    collapsible: Boolean = false,
    initiallyExpanded: Boolean = true,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RCard))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(RCard))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = TextDark,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = if (collapsible) Modifier.weight(1f) else Modifier,
            )
            if (collapsible) {
                Text(
                    text = if (expanded) "收起" else "展开",
                    color = Accent,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        if (hint != null) {
            Text(text = hint, color = SubDark, fontSize = 12.sp, lineHeight = 18.sp)
        }
        if (!collapsible || expanded) content()
    }
}

/**
 * 主操作按钮（实心）。
 * [enabled] 为 false 时自动降级为禁用态——避免用户点了没反应却不知为何。
 */
@Composable
fun ActionButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Accent,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = if (enabled) color else Card2Dark
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(RField))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) Color.White else SubDark,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 次级按钮（描边）。
 * 与主按钮拉开层级——之前所有按钮都是实心，视觉权重完全一样。
 */
@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = Accent,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(RField))
            .background(Card2Dark.copy(alpha = 0.5f))
            .border(1.dp, if (enabled) tint.copy(alpha = 0.55f) else SubDark.copy(alpha = 0.25f), RoundedCornerShape(RField))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) tint else SubDark.copy(alpha = 0.6f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 一组互斥的胶囊选项。
 *
 * 关键修复：原来用 Row 硬排，选项一多就超出屏幕被裁掉（4 个选项时必然发生）。
 * 改用 FlowRow 自动换行，任何数量都不会被裁。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipGroup(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { opt ->
            val isOn = opt == selected
            Box(
                modifier = Modifier
                    .heightIn(min = 34.dp)
                    // 未选中态提亮到 #232B29，与卡片底 #161B1B 拉开边界
                    .clip(RoundedCornerShape(17.dp))
                    .background(if (isOn) Accent else Card2Dark)
                    .border(
                        1.dp,
                        if (isOn) Accent else SubDark.copy(alpha = 0.28f),
                        RoundedCornerShape(17.dp),
                    )
                    .clickable { onSelect(opt) }
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = opt,
                    color = if (isOn) Color.White else TextDark,
                    fontSize = 12.5.sp,
                    fontWeight = if (isOn) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

/** 带标题标签的输入框。[suffix] 用于单位后缀，避免把单位塞进 label 里。 */
@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    suffix: String = "",
    singleLine: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) {
            Text(text = label, color = SubDark, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                if (placeholder.isNotEmpty()) {
                    Text(placeholder, color = SubDark.copy(alpha = 0.7f), fontSize = 14.sp)
                }
            },
            trailingIcon = {
                if (suffix.isNotEmpty()) {
                    Text(
                        suffix,
                        color = SubDark.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
            },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, color = TextDark),
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            shape = RoundedCornerShape(RField),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = FieldDark,
                unfocusedContainerColor = FieldDark,
                focusedBorderColor = Accent,
                unfocusedBorderColor = SubDark.copy(alpha = 0.22f),
                cursorColor = Accent,
                focusedTextColor = TextDark,
                unfocusedTextColor = TextDark,
            ),
        )
    }
}

/**
 * 数字输入框。
 *
 * 单独实现而不用 [LabeledField]，是因为数字框有两个特有陷阱：
 *
 * 1. 若把 `text.toIntOrNull() ?: 默认值` 直接写回配置，用户删空输入框的瞬间
 *    值会被弹回默认值，输入框里立刻又出现那串数字，想改数值永远改不成。
 *    所以「用户正在敲的原始文本」留在本地 state，空串 / 半成品不污染配置。
 * 2. 越界值若只夹紧配置而不纠正文本，界面会显示 99999 而实际生效 5000，
 *    用户完全看不出真实值。所以这里文本与配置一起纠正。
 *
 * @param minValue 允许的最小值，低于它时输入会被自动拉回。
 * @param maxValue 允许的最大值。
 */
@Composable
fun NumberField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String = "",
    minValue: Int = 0,
    maxValue: Int = Int.MAX_VALUE,
) {
    // key 用 value：外部值真的变了才重置本地文本，避免每次重组都丢用户输入
    var local by remember(value) { mutableStateOf(value.toString()) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) {
            Text(text = label, color = SubDark, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        OutlinedTextField(
            value = local,
            onValueChange = { input ->
                // 数字键盘也允许粘贴字母进来，过滤掉，否则敲了没反应很难排查
                val digits = input.filter { it.isDigit() }
                val n = digits.toIntOrNull()
                if (n == null) {
                    local = digits // 空串或半成品：只更新显示，不动配置
                } else {
                    val clamped = n.coerceIn(minValue, maxValue)
                    local = clamped.toString() // 文本与配置保持一致
                    if (clamped != value) onValueChange(clamped)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                if (suffix.isNotEmpty()) {
                    Text(
                        suffix,
                        color = SubDark.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
            },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, color = TextDark),
            singleLine = true,
            shape = RoundedCornerShape(RField),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = FieldDark,
                unfocusedContainerColor = FieldDark,
                focusedBorderColor = Accent,
                unfocusedBorderColor = SubDark.copy(alpha = 0.22f),
                cursorColor = Accent,
                focusedTextColor = TextDark,
                unfocusedTextColor = TextDark,
            ),
        )
    }
}

/** 带开关的一行。[hint] 放在标签下方，说明这个开关到底影响什么。 */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    hint: String = "",
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RField))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = TextDark, fontSize = 13.5.sp)
            if (hint.isNotEmpty()) {
                Text(
                    text = hint,
                    color = SubDark,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Accent,
                uncheckedThumbColor = SubDark,
                uncheckedTrackColor = Card2Dark,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** 顶部状态徽标：圆点 + 文案，比原来的纯色胶囊更容易一眼读出状态。 */
@Composable
fun StatusBadge(alive: Boolean, text: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (alive) OkDark.copy(alpha = 0.22f) else SubDark.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (alive) OkDark else SubDark),
        )
        Text(
            text = " $text",
            color = if (alive) OkDark else SubDark,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 就绪检查项：一个圆点/对勾 + 说明 + 可选行动按钮。
 * 用来回答用户「我还需要做什么」，替代原来散落各处的说明文字。
 */
@Composable
fun CheckRow(
    done: Boolean,
    label: String,
    detail: String = "",
    actionText: String = "",
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (done) OkDark else SubDark.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (done) "✓" else "!",
                color = if (done) Color.White else SubDark,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(modifier = Modifier
            .weight(1f)
            .padding(start = 10.dp)) {
            Text(
                text = label,
                color = if (done) TextDark else Color(0xFFFFC46B),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    color = SubDark,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (actionText.isNotEmpty() && onAction != null) {
            Text(
                text = actionText,
                color = Accent,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onAction() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** 提示条：统一橙色系（此前橙、紫两色混用，风格不统一）。 */
@Composable
fun NoticeBar(
    title: String,
    body: String,
    actionText: String = "",
    actionColor: Color = Color(0xFFBA7517),
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RField))
            .background(Color(0xFF2E2415))
            .border(1.dp, Color(0xFF4A3A1E), RoundedCornerShape(RField))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            color = Color(0xFFFFC46B),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = body,
            color = Color(0xFFE8DCC4),
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        if (actionText.isNotEmpty() && onAction != null) {
            ActionButton(text = actionText, color = actionColor) { onAction() }
        }
    }
}

/** 空状态：给日志区用，避免只有一行「暂无记录」显得像出错。 */
@Composable
fun EmptyState(icon: String, title: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = icon, fontSize = 26.sp)
        Text(
            text = title,
            color = TextDark,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = detail,
            color = SubDark,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

