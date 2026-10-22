package app.phoneinput.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.phoneinput.android.AppSettings
import app.phoneinput.android.Transport
import app.phoneinput.android.stream.LinkState
import app.phoneinput.android.stream.StreamController
import app.phoneinput.android.stream.StreamUiState
import app.phoneinput.audio.Formats
import app.phoneinput.ui.components.AccentButton
import app.phoneinput.ui.components.Banner
import app.phoneinput.ui.components.BarIconButton
import app.phoneinput.ui.components.OutlineButton
import app.phoneinput.ui.components.LevelMeter
import app.phoneinput.ui.components.PmCard
import app.phoneinput.ui.components.RoundIconButton
import app.phoneinput.ui.components.SectionLabel
import app.phoneinput.ui.components.StatTile
import app.phoneinput.ui.components.StatusChip
import app.phoneinput.ui.icons.PmIcons
import app.phoneinput.ui.theme.PmColors
import app.phoneinput.ui.theme.PmMono
import kotlinx.coroutines.delay

@Composable
fun LiveScreen(
    stream: StreamUiState,
    settings: AppSettings,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onStop: () -> Unit,
) {
    val keepScreenOn by settings.keepScreenOn.state.collectAsState()
    val micSource by settings.micSource.collectAsState()
    val aec by settings.echoCancellation.state.collectAsState()
    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    var micPickerOpen by remember { mutableStateOf(false) }

    val muted = stream.muted
    val (statusText, statusColor) = when {
        stream.link == LinkState.Connecting -> "Connecting…" to PmColors.Warn
        stream.link == LinkState.Reconnecting -> "Reconnecting…" to PmColors.Warn
        stream.link == LinkState.Standby -> "Standby" to PmColors.TextMuted
        muted -> "Muted" to PmColors.Warn
        else -> "Live" to PmColors.Accent
    }
    val live = stream.link == LinkState.Live

    val header: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            BarIconButton(PmIcons.Back, "Back to computers", onBack, Modifier.padding(end = 0.dp))
            Spacer(Modifier.weight(1f))
            StatusChip(statusText, statusColor)
            Spacer(Modifier.weight(1f))
            BarIconButton(PmIcons.Sliders, "Settings", onSettings)
        }
    }
    val details: @Composable (Modifier) -> Unit = { m ->
        Column(m.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            stream.speakerMessage?.let { Banner(it, PmColors.Warn) }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Streaming to", color = PmColors.TextMuted, fontSize = 13.sp)
                Text(stream.host?.name ?: "—", color = PmColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                val host = stream.host
                val transport = host?.transport ?: Transport.WiFi
                val subtitle = if (host == null || host.usb || host.address == host.name) transport.label else "${transport.label} · ${host.address}"
                Text(subtitle, color = PmColors.TextMuted, fontSize = 13.sp, fontFamily = PmMono)
            }

            PmCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Input level", Modifier.weight(1f))
                    Text(Formats.dbfs(stream.levelDb), color = PmColors.Text, fontSize = 15.sp, fontFamily = PmMono)
                }
                LevelMeter(stream.levels, barCount = 36, active = !muted, modifier = Modifier.fillMaxWidth().height(96.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("−60", "−40", "−20", "0 dB").forEach { Text(it, color = PmColors.TextMuted, fontSize = 12.sp, fontFamily = PmMono) }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Round trip", stream.rttMs?.takeIf { live }?.let { "$it ms" } ?: "—", Modifier.weight(1f))
                    StatTile("Quality", stream.codec?.let { "${it.label} 48k" } ?: "48 kHz", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Microphone", micSource.label, Modifier.weight(1f), mono = false)
                    StatTile("Session", Formats.duration(now - stream.startedAtMs), Modifier.weight(1f))
                }
            }
        }
    }
    val controls: @Composable () -> Unit = {
        val speakerLabel = when {
            !stream.speaker -> "Play computer audio here"
            stream.speakerOn -> "Playing computer audio"
            else -> "Starting computer audio…"
        }
        if (stream.speaker) {
            AccentButton(speakerLabel, onClick = { StreamController.setSpeaker(false) }, icon = PmIcons.Speaker, modifier = Modifier.fillMaxWidth())
        } else {
            OutlineButton(speakerLabel, onClick = { StreamController.setSpeaker(true) }, icon = PmIcons.Speaker, enabled = live || stream.link == LinkState.Standby, modifier = Modifier.fillMaxWidth())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(PmIcons.Stop, "Stop streaming", onStop, 64.dp, PmColors.DangerBg, PmColors.Danger)
            RoundIconButton(
                icon = if (muted) PmIcons.MicOff else PmIcons.Mic,
                label = if (muted) "Unmute microphone" else "Mute microphone",
                onClick = { StreamController.setMuted(!muted) },
                size = 96.dp,
                container = if (muted) PmColors.Warn else PmColors.Accent,
                content = if (muted) PmColors.OnWarn else PmColors.OnAccent,
            )
            RoundIconButton(PmIcons.Swap, "Switch microphone", { micPickerOpen = true }, 64.dp, PmColors.Surface2, PmColors.Text)
        }
        Text(
            when {
                stream.link == LinkState.Standby -> "Connected · the computer is using another device"
                muted -> "Muted: the computer hears silence"
                stream.speaker && !aec -> "Tap to mute · the mic can pick up the computer's audio; mute it or turn on echo cancellation"
                else -> "Tap to mute"
            },
            color = if (muted) PmColors.Warn else PmColors.TextMuted,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight && maxHeight < 600.dp) {
            // Landscape phone: the details scroll on the left, the controls stay reachable on the right.
            Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp)) {
                header()
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    details(Modifier.weight(1f).fillMaxHeight())
                    Column(
                        Modifier.weight(0.8f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    ) { controls() }
                }
            }
        } else {
            // Header and controls stay pinned; only the middle scrolls on short screens.
            Column(
                Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                header()
                details(Modifier.weight(1f))
                controls()
            }
        }
    }

    if (micPickerOpen) {
        MicSourceDialog(current = micSource, onPick = {
            settings.setMicSource(it)
            micPickerOpen = false
        }, onDismiss = { micPickerOpen = false })
    }
}

