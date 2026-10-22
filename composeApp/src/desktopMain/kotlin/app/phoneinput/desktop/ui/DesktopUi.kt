package app.phoneinput.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.phoneinput.audio.AudioLevels
import app.phoneinput.audio.Formats
import app.phoneinput.desktop.DesktopController
import app.phoneinput.desktop.DesktopState
import app.phoneinput.desktop.audio.InstallState
import app.phoneinput.desktop.net.StreamServer
import app.phoneinput.protocol.PhoneInputProtocol
import app.phoneinput.ui.components.AccentButton
import app.phoneinput.ui.components.Banner
import app.phoneinput.ui.components.LevelMeter
import app.phoneinput.ui.components.OutlineButton
import app.phoneinput.ui.components.PmCard
import app.phoneinput.ui.components.RoundIconButton
import app.phoneinput.ui.components.SectionLabel
import app.phoneinput.ui.components.StatusChip
import app.phoneinput.ui.components.SwitchRow
import app.phoneinput.ui.icons.PmIcons
import app.phoneinput.ui.theme.PmColors
import app.phoneinput.ui.theme.PmMono
import kotlin.math.roundToInt

/** Small status pill shown in the title bar. */
@Composable
fun TitleStatus(state: DesktopState) {
    val active = state.active
    val (text, color) = when {
        active != null && state.muted -> "Muted · ${active.name}" to PmColors.Warn
        active != null -> "Live · ${active.name}" to PmColors.Accent
        !state.virtualMic.ready -> state.virtualMic.deviceName to PmColors.Warn
        else -> "Waiting for a device" to PmColors.TextMuted
    }
    Row(
        Modifier.clip(CircleShape).background(PmColors.Surface).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(text, color = PmColors.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
    }
}

@Composable
fun DesktopRoot(state: DesktopState, controller: DesktopController) {
    var showQr by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        DesktopLayout(state, controller, onShowQr = { showQr = true })
        if (showQr) QrOverlay(state, onClose = { showQr = false })
    }
}

@Composable
private fun DesktopLayout(state: DesktopState, controller: DesktopController, onShowQr: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(PmColors.Bg)) {
        if (maxWidth >= 820.dp) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(state, controller, onShowQr, Modifier.width(280.dp).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(PmColors.Border))
                MainPane(state, controller, Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()))
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Sidebar(state, controller, onShowQr, Modifier.fillMaxWidth(), scroll = false)
                Box(Modifier.fillMaxWidth().height(1.dp).background(PmColors.Border))
                MainPane(state, controller, Modifier.fillMaxWidth())
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sidebar: device switcher + this computer

@Composable
private fun Sidebar(state: DesktopState, controller: DesktopController, onShowQr: () -> Unit, modifier: Modifier, scroll: Boolean = true) {
    Column(modifier.background(PmColors.Bg).padding(horizontal = 14.dp, vertical = 18.dp)) {
        Column(
            (if (scroll) Modifier.weight(1f).verticalScroll(rememberScrollState()) else Modifier).fillMaxWidth().selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.padding(start = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Devices", Modifier.weight(1f))
                if (state.connected.size > 1) Text("Ctrl+1…${state.connected.size.coerceAtMost(9)} to switch", color = PmColors.TextMuted, fontSize = 11.sp)
            }
            if (state.connected.isEmpty()) {
                Text(
                    "No devices connected yet.",
                    color = PmColors.TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
            state.connected.forEachIndexed { i, device ->
                DeviceRow(device, index = i, level = state.deviceLevels[device.sessionId], muted = state.muted, onSelect = { controller.select(device.sessionId) })
            }
            val recent = state.devices.filter { !it.connected }
            if (recent.isNotEmpty()) {
                Row(Modifier.padding(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Recent", Modifier.weight(1f))
                    TextLink("Clear", onClick = controller::clearRecent)
                }
                recent.forEach { RecentRow(it, onForget = { controller.forgetRecent(it.sessionId) }) }
            }
        }
        ThisComputer(state, onShowQr)
    }
}

@Composable
private fun DeviceRow(device: StreamServer.Device, index: Int, level: Float?, muted: Boolean, onSelect: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        device.active -> PmColors.Surface2
        hovered -> PmColors.Surface
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .then(if (device.active) Modifier.border(1.dp, PmColors.Accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp)) else Modifier)
            .selectable(selected = device.active, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(if (device.active) PmColors.AccentBg else PmColors.Surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(PmIcons.Phone, contentDescription = null, tint = if (device.active) PmColors.Accent else PmColors.TextMuted, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(device.name, color = PmColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    device.active && muted -> Text("Active · muted", color = PmColors.Warn, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    device.active -> Text("Active mic", color = PmColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    else -> Text("Standby", color = PmColors.TextMuted, fontSize = 12.sp)
                }
                when (device.transport) {
                    app.phoneinput.desktop.net.Transport.Usb ->
                        Icon(PmIcons.Usb, contentDescription = "USB", tint = PmColors.TextMuted, modifier = Modifier.size(12.dp))
                    app.phoneinput.desktop.net.Transport.Bluetooth ->
                        Icon(PmIcons.Bluetooth, contentDescription = "Bluetooth", tint = PmColors.TextMuted, modifier = Modifier.size(12.dp))
                    app.phoneinput.desktop.net.Transport.WiFi -> Unit
                }
                if (device.speaker) {
                    Icon(PmIcons.Speaker, contentDescription = "Playing this computer's audio", tint = PmColors.Accent, modifier = Modifier.size(12.dp))
                }
                MiniMeter(level, active = device.active, modifier = Modifier.weight(1f))
            }
        }
        if (index < 9) {
            Text(
                "${index + 1}",
                color = PmColors.TextMuted,
                fontSize = 11.sp,
                fontFamily = PmMono,
                modifier = Modifier.clip(RoundedCornerShape(5.dp)).border(1.dp, PmColors.Border, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun MiniMeter(levelDb: Float?, active: Boolean, modifier: Modifier) {
    val v = AudioLevels.toMeter(levelDb ?: AudioLevels.FLOOR_DB)
    Box(modifier.height(4.dp).clip(CircleShape).background(PmColors.Border)) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(v.coerceIn(0.02f, 1f))
                .background(if (active) PmColors.Accent else PmColors.TextMuted.copy(alpha = 0.6f)),
        )
    }
}

@Composable
private fun RecentRow(device: StreamServer.Device, onForget: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(PmIcons.Phone, contentDescription = null, tint = PmColors.TextMuted.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, color = PmColors.TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(device.address, color = PmColors.TextMuted.copy(alpha = 0.75f), fontSize = 11.sp, fontFamily = PmMono)
        }
        RoundIconButton(PmIcons.Close, "Remove ${device.name} from Recent", onForget, 32.dp, Color.Transparent, PmColors.TextMuted, iconSize = 14.dp)
    }
}

@Composable
private fun ThisComputer(state: DesktopState, onShowQr: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).background(PmColors.Surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SectionLabel("This computer")
        Text(state.hostName, color = PmColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        state.addresses.forEach { Text("$it:${PhoneInputProtocol.AUDIO_PORT}", color = PmColors.TextMuted, fontSize = 12.sp, fontFamily = PmMono) }
        if (state.addresses.isEmpty()) Text("Not on a network", color = PmColors.Warn, fontSize = 12.sp)
        val usbReady = state.usb.devices.count { !it.wireless && it.state == app.phoneinput.desktop.net.UsbDevice.State.Ready }
        Text(
            when {
                !state.usb.adbFound -> "USB: not set up"
                usbReady > 0 -> "USB: $usbReady device${if (usbReady > 1) "s" else ""} ready"
                else -> "USB: no cable connected"
            },
            color = if (usbReady > 0) PmColors.Accent else PmColors.TextMuted,
            fontSize = 12.sp,
        )
        if (state.bluetooth.supported) {
            Text(
                if (state.bluetooth.listening) "Bluetooth: ready" else "Bluetooth: off",
                color = if (state.bluetooth.listening) PmColors.Accent else PmColors.TextMuted,
                fontSize = 12.sp,
            )
        }
        if (state.pairingUri != null) TextLink("Show QR code", onClick = onShowQr)
    }
}

// ---------------------------------------------------------------------------------------------
// Main pane

@Composable
private fun MainPane(state: DesktopState, controller: DesktopController, modifier: Modifier) {
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        state.serverError?.let { Banner(it, PmColors.Danger) }
        if (state.outputError) Banner("Couldn't play into ${state.virtualMic.deviceName}. Retrying…", PmColors.Warn)
        if (state.active == null) {
            EmptyState(state, controller)
        } else {
            ActiveCard(state, controller)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val twoColumns = maxWidth >= 640.dp
                if (twoColumns) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutputCard(state, controller, Modifier.weight(1f))
                        SoundCard(state, controller, Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutputCard(state, controller, Modifier.fillMaxWidth())
                        SoundCard(state, controller, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(state: DesktopState, controller: DesktopController) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 520.dp).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically),
    ) {
        ConnectIllustration()
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connect a phone or tablet", color = PmColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                "PhoneInput turns it into a microphone for this computer. Several devices can stay connected; pick the one to use from the list.",
                color = PmColors.TextMuted,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 520.dp),
            )
        }
        BoxWithConstraints(Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
            val steps: List<@Composable (Modifier) -> Unit> = listOf(
                { m -> Step(m, 1, "Open PhoneInput", "Install the Android app and open it on your phone or tablet.") },
                { m -> Step(m, 2, "Scan the QR code", "Tap QR in the app and point it at this screen. It connects right away.") },
                { m -> Step(m, 3, "Or pick from the list", "On the same Wi-Fi, ${state.hostName} appears in the app. With a cable choose USB; if paired, Bluetooth.") },
            )
            if (maxWidth >= 720.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    QrCard(state, Modifier.width(260.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        steps.forEach { it(Modifier.fillMaxWidth()) }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    QrCard(state, Modifier.fillMaxWidth())
                    steps.forEach { it(Modifier.fillMaxWidth()) }
                }
            }
        }
        UsbCard(state.usb, onInstallAdb = controller::installAdb, modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth())
        BluetoothCard(state.bluetooth, modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth())
        if (!state.virtualMic.ready) {
            OutputCard(state, controller, Modifier.widthIn(max = 560.dp).fillMaxWidth())
        } else {
            Row(
                Modifier.clip(CircleShape).background(PmColors.Surface).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(PmIcons.Check, contentDescription = null, tint = PmColors.Accent, modifier = Modifier.size(16.dp))
                Text("Apps will hear it as “${state.virtualMic.deviceName}”", color = PmColors.TextMuted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ConnectIllustration() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        IllustrationTile(PmIcons.Phone, 72.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(0.35f, 0.6f, 1f, 0.6f, 0.35f).forEach { a ->
                Box(Modifier.size(width = 6.dp, height = (28 * a + 8).dp).clip(CircleShape).background(PmColors.Accent.copy(alpha = 0.25f + 0.6f * a)))
            }
        }
        IllustrationTile(PmIcons.Monitor, 88.dp)
    }
}

@Composable
private fun IllustrationTile(icon: ImageVector, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(22.dp)).background(PmColors.Surface).border(1.dp, PmColors.Border, RoundedCornerShape(22.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = PmColors.Text, modifier = Modifier.size(size * 0.42f))
    }
}

@Composable
private fun Step(modifier: Modifier, number: Int, title: String, body: String) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(PmColors.Surface).padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(PmColors.AccentBg), contentAlignment = Alignment.Center) {
            Text("$number", color = PmColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = PmColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(body, color = PmColors.TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun ActiveCard(state: DesktopState, controller: DesktopController) {
    val active = state.active ?: return
    PmCard(padding = PaddingValues(horizontal = 24.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(active.name, color = PmColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    StatusChip(
                        text = if (state.muted) "Muted" else "Live",
                        color = if (state.muted) PmColors.Warn else PmColors.Accent,
                        background = if (state.muted) PmColors.Surface2 else PmColors.AccentBg,
                    )
                }
                Text(
                    "${active.transport.label}${if (active.transport == app.phoneinput.desktop.net.Transport.WiFi) " · " + active.address else ""} · ${active.codec.label} · buffer ${state.bufferMs} ms · loss ${"%.1f".format(state.lossPercent)}%",
                    color = PmColors.TextMuted,
                    fontSize = 12.sp,
                    fontFamily = PmMono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AccentButton(
                text = if (state.muted) "Unmute" else "Mute",
                onClick = { controller.setMuted(!state.muted) },
                icon = if (state.muted) PmIcons.Mic else PmIcons.MicOff,
                container = if (state.muted) PmColors.Warn else PmColors.Surface2,
                content = if (state.muted) PmColors.OnWarn else PmColors.Text,
                height = 40.dp,
            )
            OutlineButton("Disconnect", onClick = { controller.disconnect(active.sessionId) }, height = 40.dp, radius = 10.dp)
        }
        LevelMeter(state.levels, barCount = 72, active = !state.muted, modifier = Modifier.fillMaxWidth().height(80.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("Input level  (Ctrl+M to mute)", color = PmColors.TextMuted, fontSize = 12.sp, fontFamily = PmMono, modifier = Modifier.weight(1f))
            Text("${Formats.dbfs(state.rmsDb)} · peak ${Formats.db(state.peakDb)}", color = PmColors.TextMuted, fontSize = 12.sp, fontFamily = PmMono)
        }
        if (state.connected.size > 1) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(PmColors.Border))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Switch to", color = PmColors.TextMuted, fontSize = 13.sp)
                state.connected.filter { !it.active }.take(4).forEach { d ->
                    SwitchChip(d.name, onClick = { controller.select(d.sessionId) })
                }
            }
        }
    }
}

@Composable
private fun SwitchChip(name: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .border(BorderStroke(1.dp, PmColors.BorderStrong), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(PmIcons.Phone, contentDescription = null, tint = PmColors.TextMuted, modifier = Modifier.size(14.dp))
        Text(name, color = PmColors.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
    }
}

@Composable
private fun OutputCard(state: DesktopState, controller: DesktopController, modifier: Modifier) {
    val vm = state.virtualMic
    PmCard(modifier) {
        Text("Output", color = PmColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(
            if (vm.ready) "Pick this as the microphone in Discord, Zoom, OBS or your recorder." else vm.help ?: "",
            color = PmColors.TextMuted,
            fontSize = 14.sp,
            lineHeight = 21.sp,
        )
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PmColors.Surface2).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(if (vm.ready) PmIcons.Mic else PmIcons.Alert, contentDescription = null, tint = if (vm.ready) PmColors.Accent else PmColors.Warn, modifier = Modifier.size(18.dp))
            Text(vm.deviceName, color = PmColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        if (!vm.ready && state.canInstallDriver) {
            DriverInstall(state, controller)
        } else {
            TextLink("Open sound settings", onClick = controller::openSoundSettings)
        }
    }
}

@Composable
private fun DriverInstall(state: DesktopState, controller: DesktopController) {
    when (val install = state.driverInstall) {
        is InstallState.Downloading -> {
            Text("Downloading VB-CABLE…", color = PmColors.Text, fontSize = 14.sp)
            if (install.progress != null) {
                LinearProgressIndicator(progress = { install.progress }, modifier = Modifier.fillMaxWidth(), color = PmColors.Accent, trackColor = PmColors.Border)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = PmColors.Accent, trackColor = PmColors.Border)
            }
        }
        InstallState.WaitingForSetup -> {
            Text(
                "Finish in the VB-CABLE window: click “Install Driver”, then close it when it says it's done.",
                color = PmColors.Text,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = PmColors.Accent, trackColor = PmColors.Border)
        }
        InstallState.NeedsRestart -> {
            Banner("VB-CABLE is installed. Restart your computer to finish, then open PhoneInput again.", PmColors.Warn)
            TextLink("Check again", onClick = controller::recheckVirtualMic)
        }
        is InstallState.Failed, InstallState.Idle -> {
            if (install is InstallState.Failed) Banner(install.message, PmColors.Danger)
            AccentButton(text = if (install is InstallState.Failed) "Try again" else "Install VB-CABLE", onClick = controller::installDriver, height = 40.dp)
            Text(
                "Downloads the free VB-CABLE driver (donationware by VB-Audio) and opens its installer. Windows will ask for permission.",
                color = PmColors.TextMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TextLink("Check again", onClick = controller::recheckVirtualMic)
                state.virtualMic.helpUrl?.let { url -> TextLink("About VB-CABLE", onClick = { controller.openUrl(url) }) }
            }
        }
    }
}

@Composable
private fun SoundCard(state: DesktopState, controller: DesktopController, modifier: Modifier) {
    PmCard(modifier) {
        Text("Sound", color = PmColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("Gain", color = PmColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(Formats.signedDb(state.gainDb), color = PmColors.TextMuted, fontSize = 14.sp, fontFamily = PmMono)
            }
            Slider(
                value = state.gainDb,
                onValueChange = { controller.setGain(it.roundToInt().toFloat()) },
                valueRange = -12f..24f,
                modifier = Modifier.semantics {
                    contentDescription = "Gain"
                    stateDescription = Formats.signedDb(state.gainDb)
                },
                colors = SliderDefaults.colors(thumbColor = PmColors.Accent, activeTrackColor = PmColors.Accent, inactiveTrackColor = PmColors.Border),
            )
        }
        SwitchRow(
            title = "Listen on this computer",
            subtitle = "Play the active device through your speakers",
            checked = state.monitor,
            onCheckedChange = controller::setMonitor,
            minHeight = 44.dp,
            padding = PaddingValues(0.dp),
        )
        Text("Noise suppression is set on each phone (Settings › Noise suppression).", color = PmColors.TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun TextLink(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = PmColors.Text,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
    )
}
