package org.example.smsforwarder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.smsforwarder.ui.theme.Accent
import org.example.smsforwarder.ui.theme.Card2Dark
import org.example.smsforwarder.ui.theme.FieldDark
import org.example.smsforwarder.ui.theme.SubDark
import org.example.smsforwarder.ui.theme.TextDark

/** 带标题的圆角卡片。 */
@Composable
fun SectionCard(
    title: String,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            color = TextDark,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        if (hint != null) {
            Text(text = hint, color = SubDark, fontSize = 12.sp, lineHeight = 17.sp)
        }
        content()
    }
}

/** 主操作按钮。 */
@Composable
fun ActionButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Accent,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .background(color, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 一组互斥的胶囊选项。 */
@Composable
fun ChipGroup(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { opt ->
            val isOn = opt == selected
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .background(
                        if (isOn) Accent else Card2Dark,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { onSelect(opt) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = opt,
                    color = if (isOn) Color.White else TextDark,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** 带标题标签的输入框。 */
@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    numeric: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) {
            Text(text = label, color = SubDark, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
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
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, color = TextDark),
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            shape = RoundedCornerShape(8.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = FieldDark,
                unfocusedContainerColor = FieldDark,
                focusedBorderColor = Accent,
                unfocusedBorderColor = Color.Transparent,
                cursorColor = Accent,
                focusedTextColor = TextDark,
                unfocusedTextColor = TextDark,
            ),
        )
    }
}

/** 带开关的一行。 */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = SubDark,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
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
