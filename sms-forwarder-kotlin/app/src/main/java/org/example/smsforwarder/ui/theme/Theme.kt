package org.example.smsforwarder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 深青绿主题（与 Python 版保持一致的视觉语言）
val Accent = Color(0xFF0F6E56)
val AccentDark = Color(0xFF0B4638)
val AccentLight = Color(0xFF7FE3C0)
val BgDark = Color(0xFF0B0E0E)
val CardDark = Color(0xFF161B1B)
val Card2Dark = Color(0xFF1D2422)
val FieldDark = Color(0xFF242D2B)
val TextDark = Color(0xFFE0E8E5)
val SubDark = Color(0xFF8BA39C)
val DangerDark = Color(0xFF8B3232)
val OkDark = Color(0xFF3C9E70)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentDark,
    onPrimaryContainer = AccentLight,
    secondary = SubDark,
    onSecondary = Color.White,
    background = BgDark,
    onBackground = TextDark,
    surface = CardDark,
    onSurface = TextDark,
    surfaceVariant = Card2Dark,
    onSurfaceVariant = SubDark,
    outline = Color(0xFF2D3A37),
    error = DangerDark,
    onError = Color.White,
)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentLight,
    onPrimaryContainer = AccentDark,
    background = Color(0xFFF5F7F6),
    onBackground = Color(0xFF0B0E0E),
    surface = Color.White,
    onSurface = Color(0xFF0B0E0E),
    surfaceVariant = Color(0xFFE6EBE9),
    onSurfaceVariant = Color(0xFF4A5A56),
    error = DangerDark,
)

@Composable
fun SmsForwarderTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    // 默认强制深色，保持与原设计一致；如需跟随系统可改为 isSystemInDarkTheme()
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
