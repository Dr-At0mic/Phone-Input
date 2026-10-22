package app.phoneinput.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.phoneinput.audio.AudioLevels
import app.phoneinput.ui.icons.PmIcons
import app.phoneinput.ui.theme.PmColors
import app.phoneinput.ui.theme.PmMono

/**
 * Bar meter of recent levels (0..1), newest on the right. Decorative: pair it with a text readout.
 */
@Composable
fun LevelMeter(
    levels: List<Float>,
    barCount: Int,
    active: Boolean,
    modifier: Modifier = Modifier,
    gap: Dp = 3.dp,
) {
    Canvas(modifier) {
        val gapPx = gap.toPx()
        val barW = ((size.width - gapPx * (barCount - 1)) / barCount).coerceAtLeast(1f)
        val radius = CornerRadius(2.dp.toPx())
        for (i in 0 until barCount) {
            val idx = levels.size - barCount + i
            val v = if (active && idx >= 0) levels[idx] else 0f
            val h = size.height * if (active) 0.06f + v * 0.94f else 0.05f
            val color = when {
                !active -> PmColors.Border
                v > AudioLevels.HOT_METER -> PmColors.Warn
                else -> PmColors.Accent
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barW + gapPx), (size.height - h) / 2),
                size = Size(barW, h),
                cornerRadius = radius,
            )
        }
    }
}

@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier, background: Color = PmColors.Surface) {
    Row(
        modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = PmColors.TextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
    )
}

@Composable
fun PmCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(PmColors.Surface).padding(padding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = true) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(PmColors.Surface).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, color = PmColors.TextMuted, fontSize = 13.sp)
        Text(
            value,
            color = PmColors.Text,
            fontSize = if (mono) 20.sp else 16.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (mono) PmMono else null,
            maxLines = 1,
        )
    }
}

@Composable
fun PmSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = PmColors.OnAccent,
            checkedTrackColor = PmColors.Accent,
            checkedBorderColor = PmColors.Accent,
            uncheckedThumbColor = PmColors.TextMuted,
            uncheckedTrackColor = PmColors.Border,
            uncheckedBorderColor = PmColors.SwitchOffBorder,
        ),
    )
}

/** A full-row switch: the whole row toggles and is announced as one switch. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    minHeight: Dp = 64.dp,
    padding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowTexts(title, subtitle)
        PmSwitch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun NavRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowTexts(title, subtitle)
        if (onClick != null) {
            Icon(PmIcons.Chevron, contentDescription = null, tint = PmColors.TextMuted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun RowScope.RowTexts(title: String, subtitle: String?) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = PmColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        if (subtitle != null) Text(subtitle, color = PmColors.TextMuted, fontSize = 13.sp)
    }
}

@Composable
fun RowDivider(startIndent: Dp = 16.dp) {
    Box(Modifier.fillMaxWidth().padding(start = startIndent).height(1.dp).background(PmColors.Border))
}

@Composable
fun AccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    container: Color = PmColors.Accent,
    content: Color = PmColors.OnAccent,
    height: Dp = 44.dp,
    enabled: Boolean = true,
    horizontalPadding: Dp = 18.dp,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(height / 2),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = PaddingValues(horizontal = horizontalPadding),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 44.dp,
    radius: Dp = height / 2,
    enabled: Boolean = true,
    horizontalPadding: Dp = 18.dp,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(radius),
        border = androidx.compose.foundation.BorderStroke(1.dp, PmColors.BorderStrong),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = PmColors.Text),
        contentPadding = PaddingValues(horizontal = horizontalPadding),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

/** Round icon-only button with an accessible label. */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    size: Dp,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = size * 0.375f,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(iconSize))
    }
}

/** 48dp transparent icon button for app bars. */
@Composable
fun BarIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    RoundIconButton(icon, label, onClick, size, Color.Transparent, PmColors.Text, modifier, iconSize = 22.dp)
}

@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 40.dp, tint: Color = PmColors.Text) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(12.dp)).background(PmColors.Surface2),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size / 2))
    }
}

@Composable
fun Banner(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PmColors.Surface)
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PmIcons.Alert, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, color = PmColors.Text, fontSize = 14.sp)
    }
}
