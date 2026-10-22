package app.phoneinput.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.phoneinput.ui.icons.PmIcons
import app.phoneinput.ui.theme.PmColors
import app.phoneinput.update.UpdateState

/**
 * "A new version is available" card. Shows nothing unless an update was found, so callers can
 * place it unconditionally. [installLabel] differs per platform ("Restart to update" / "Install").
 */
@Composable
fun UpdateBanner(
    state: UpdateState,
    installLabel: String,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenPage: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (title, body) = when (state) {
        is UpdateState.Available -> "PhoneInput ${state.release.version} is available" to
            if (state.asset != null) "Download it now; installing takes a moment and restarts PhoneInput." else "Download it from the releases page."
        is UpdateState.Downloading -> "Downloading PhoneInput ${state.release.version}" to
            (state.progress?.let { "${(it * 100).toInt()}%" } ?: "Starting…")
        is UpdateState.Ready -> "PhoneInput ${state.release.version} is ready" to "Install it now. Streaming stops while PhoneInput restarts."
        is UpdateState.Installing -> "Installing PhoneInput ${state.release.version}" to "Confirm the installer prompt to continue."
        else -> return
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PmColors.Surface)
            .border(1.dp, PmColors.Accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(PmIcons.AppMark, contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = PmColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(body, color = PmColors.TextMuted, fontSize = 13.sp)
            }
        }
        val buttons: List<Pair<String, () -> Unit>> = when (state) {
            is UpdateState.Available -> if (state.asset != null) listOf("Download" to onDownload) else listOf("Open releases" to { onOpenPage(state.release.pageUrl) })
            is UpdateState.Ready -> listOf(installLabel to onInstall)
            else -> emptyList()
        }
        if (buttons.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                buttons.forEach { (label, action) -> AccentButton(label, onClick = action, height = 36.dp) }
                OutlineButton("Later", onClick = onDismiss, height = 36.dp)
            }
        }
    }
}

/** One-line status for a settings row ("Up to date", "Downloading 40%", …). */
fun UpdateState.summary(): String = when (this) {
    UpdateState.Disabled -> "Development build, updates are off"
    UpdateState.Idle -> "Tap to check for updates"
    UpdateState.Checking -> "Checking…"
    UpdateState.UpToDate -> "Up to date"
    is UpdateState.Available -> "Version ${release.version} available"
    is UpdateState.Downloading -> "Downloading ${release.version}" + (progress?.let { " · ${(it * 100).toInt()}%" } ?: "")
    is UpdateState.Ready -> "Version ${release.version} ready to install"
    is UpdateState.Installing -> "Installing ${release.version}…"
    is UpdateState.Failed -> message
}
