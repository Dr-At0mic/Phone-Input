package app.phoneinput.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.phoneinput.android.AppSettings
import app.phoneinput.android.MicSource
import app.phoneinput.android.update.AppUpdates
import app.phoneinput.ui.components.summary
import app.phoneinput.update.UpdateState
import androidx.compose.ui.platform.LocalContext
import app.phoneinput.audio.NoiseSuppression
import app.phoneinput.ui.components.BarIconButton
import app.phoneinput.ui.components.NavRow
import app.phoneinput.ui.components.RowDivider
import app.phoneinput.ui.components.SectionLabel
import app.phoneinput.ui.components.SwitchRow
import app.phoneinput.ui.icons.PmIcons
import app.phoneinput.ui.theme.PmColors

@Composable
fun SettingsScreen(settings: AppSettings, onBack: () -> Unit) {
    val mic by settings.micSource.collectAsState()
    val ns by settings.noiseSuppression.collectAsState()
    val aec by settings.echoCancellation.state.collectAsState()
    val agc by settings.autoGain.state.collectAsState()
    val reconnect by settings.autoReconnect.state.collectAsState()
    val compression by settings.compression.state.collectAsState()
    val screenOn by settings.keepScreenOn.state.collectAsState()
    val savedHosts by settings.savedHosts.collectAsState()
    val autoUpdate by settings.autoUpdate.state.collectAsState()
    val update by AppUpdates.state.collectAsState()
    val context = LocalContext.current
    var nsPickerOpen by remember { mutableStateOf(false) }
    var micPickerOpen by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            BarIconButton(PmIcons.Back, "Back", onBack)
            Text("Settings", color = PmColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
        }

        Group("Audio") {
            NavRow("Microphone", subtitle = mic.label, onClick = { micPickerOpen = true })
            RowDivider()
            SwitchRow(
                "Compression",
                compression,
                settings.compression::set,
                subtitle = if (compression) "Opus, 48 kHz mono · 8× less data, steadier over USB and Bluetooth" else "Uncompressed 48 kHz 16-bit PCM",
            )
            RowDivider()
            NavRow("Noise suppression", subtitle = "${ns.label}: ${ns.description}", onClick = { nsPickerOpen = true })
            RowDivider()
            SwitchRow("Echo cancellation", aec, settings.echoCancellation::set, subtitle = "Use when the computer plays sound out loud")
            RowDivider()
            SwitchRow("Automatic gain", agc, settings.autoGain::set, subtitle = "Keeps your voice at a steady level")
        }

        Group("Connection") {
            SwitchRow("Reconnect automatically", reconnect, settings.autoReconnect::set, subtitle = "Keep trying if the Wi-Fi drops")
            RowDivider()
            NavRow("Saved computers", subtitle = if (savedHosts.isEmpty()) "None yet" else "${savedHosts.size} saved · edit or remove them from the home screen")
        }

        Group("While streaming") {
            SwitchRow("Keep screen on", screenOn, settings.keepScreenOn::set, subtitle = "Streaming continues with the screen off")
        }

        Group("Updates") {
            SwitchRow("Update automatically", autoUpdate, settings.autoUpdate::set, subtitle = "Check when the app opens and download on Wi-Fi")
            RowDivider()
            NavRow(
                "PhoneInput ${AppUpdates.currentVersion ?: "dev"}",
                subtitle = update.summary(),
                onClick = { if (update !is UpdateState.Disabled) AppUpdates.check(context) },
            )
        }

        Text(
            "Noise suppression runs inside PhoneInput, so it works on every phone. Echo cancellation and automatic gain use your phone's own effects, which some phones ignore.",
            color = PmColors.TextMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    if (nsPickerOpen) {
        ChoiceDialog(
            title = "Noise suppression",
            options = NoiseSuppression.entries,
            current = ns,
            label = { it.label },
            description = { it.description },
            onPick = {
                settings.setNoiseSuppression(it)
                nsPickerOpen = false
            },
            onDismiss = { nsPickerOpen = false },
        )
    }

    if (micPickerOpen) {
        MicSourceDialog(current = mic, onPick = {
            settings.setMicSource(it)
            micPickerOpen = false
        }, onDismiss = { micPickerOpen = false })
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title, Modifier.padding(start = 4.dp))
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(PmColors.Surface), content = content)
    }
}

@Composable
fun MicSourceDialog(current: MicSource, onPick: (MicSource) -> Unit, onDismiss: () -> Unit) {
    ChoiceDialog("Microphone", MicSource.entries, current, { it.label }, { it.description }, onPick, onDismiss)
}

@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    current: T,
    label: (T) -> String,
    description: (T) -> String,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PmColors.Surface,
        title = { Text(title, color = PmColors.Text) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .selectable(selected = option == current, role = Role.RadioButton, onClick = { onPick(option) })
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option == current,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = PmColors.Accent, unselectedColor = PmColors.TextMuted),
                        )
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(label(option), color = PmColors.Text, fontSize = 16.sp)
                            Text(description(option), color = PmColors.TextMuted, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = PmColors.Accent, fontWeight = FontWeight.Bold) }
        },
    )
}
