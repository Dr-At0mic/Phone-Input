package app.phoneinput.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Design tokens from docs/design/DESIGN.md. */
object PmColors {
    val Bg = Color(0xFF0B0E13)
    val Surface = Color(0xFF151A21)
    val Surface2 = Color(0xFF1E242D)
    val Border = Color(0xFF2A313C)
    val BorderStrong = Color(0xFF3A4250)
    val Text = Color(0xFFF2F4F7)
    val TextMuted = Color(0xFFA3ADBB)
    val Accent = Color(0xFF2DD4A7)
    val OnAccent = Color(0xFF04261D)
    val AccentBg = Color(0xFF0F2A24)
    val Warn = Color(0xFFF5B544)
    val OnWarn = Color(0xFF2B1D04)
    val Danger = Color(0xFFF87171)
    val DangerBg = Color(0xFF2A1416)
    val SwitchOffBorder = Color(0xFF4A5361)
}

/** Numbers (latency, dBFS, addresses) use a monospace face. Inter / JetBrains Mono can replace the defaults later. */
val PmMono: FontFamily = FontFamily.Monospace

private val PmTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun PhoneInputTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PmColors.Accent,
            onPrimary = PmColors.OnAccent,
            secondary = PmColors.Warn,
            onSecondary = PmColors.OnWarn,
            background = PmColors.Bg,
            onBackground = PmColors.Text,
            surface = PmColors.Surface,
            onSurface = PmColors.Text,
            surfaceVariant = PmColors.Surface2,
            onSurfaceVariant = PmColors.TextMuted,
            surfaceContainer = PmColors.Surface,
            surfaceContainerHigh = PmColors.Surface2,
            surfaceContainerHighest = PmColors.Surface2,
            outline = PmColors.BorderStrong,
            outlineVariant = PmColors.Border,
            error = PmColors.Danger,
        ),
        typography = PmTypography,
        content = content,
    )
}
