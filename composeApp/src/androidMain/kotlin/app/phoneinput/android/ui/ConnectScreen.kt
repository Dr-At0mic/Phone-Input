package app.phoneinput.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.phoneinput.android.AppSettings
import app.phoneinput.android.Host
import app.phoneinput.android.Transport
import app.phoneinput.android.stream.DiscoveryClient
import app.phoneinput.android.stream.LinkState
import app.phoneinput.android.stream.StreamUiState
import app.phoneinput.protocol.HostOs
import app.phoneinput.protocol.PhoneInputProtocol
import app.phoneinput.ui.components.AccentButton
import app.phoneinput.ui.components.BarIconButton
import app.phoneinput.ui.components.Banner
import app.phoneinput.ui.components.IconTile
import app.phoneinput.ui.components.OutlineButton
import app.phoneinput.ui.components.RoundIconButton
import app.phoneinput.ui.components.RowDivider
import app.phoneinput.ui.components.SectionLabel
import app.phoneinput.ui.components.StatusChip
import app.phoneinput.ui.icons.PmIcons
import app.phoneinput.ui.theme.PmColors
import app.phoneinput.ui.theme.PmMono

/** One row of the computer list: a saved host, a discovered one, or both. */
private data class HostItem(val host: Host, val saved: Boolean, val online: Boolean)

@Composable
fun ConnectScreen(
    stream: StreamUiState,
    settings: AppSettings,
    permissionDenied: Boolean,
    onConnect: (Host) -> Unit,
    onScanQr: () -> Unit,
    qrBusy: Boolean,
    onOpenLive: () -> Unit,
    onSettings: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(Transport.WiFi) }
    val discovery = remember { DiscoveryClient() }
    DisposableEffect(Unit) {
        discovery.start()
        onDispose { discovery.stop() }
    }
    val found by discovery.hosts.collectAsState()
    val saved by settings.savedHosts.collectAsState()
    var editing by remember { mutableStateOf<Host?>(null) }
    var adding by remember { mutableStateOf(false) }
    var optionsFor by remember { mutableStateOf<Host?>(null) }

    // Saved computers keep the user's name and order; discovered ones not yet saved follow.
    val items = remember(found, saved) {
        val onlineKeys = found.map { it.key }.toSet()
        saved.map { s -> HostItem(found.firstOrNull { it.key == s.key }?.let { s.copy(os = it.os) } ?: s, saved = true, online = s.key in onlineKeys) } +
            found.filter { f -> saved.none { it.key == f.key } }.map { HostItem(it, saved = false, online = true) }
    }
    val idle = stream.link == LinkState.Idle

    val panel: @Composable (Modifier) -> Unit = { m ->
        when (mode) {
            Transport.Usb -> UsbPanel(m, enabled = idle, onConnect = { onConnect(Host.Usb) })
            Transport.Bluetooth -> BluetoothPanel(m, enabled = idle, onConnect = onConnect)
            Transport.WiFi -> Column(m.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Computers", Modifier.weight(1f))
                    Text(if (found.isEmpty()) "Searching Wi-Fi…" else "${found.size} on Wi-Fi", color = PmColors.TextMuted, fontSize = 13.sp)
                }
                if (items.isEmpty()) {
                    Text(
                        "Open PhoneInput on your Windows or Linux computer. It shows up here when both are on the same Wi-Fi.",
                        color = PmColors.TextMuted,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PmColors.Surface).padding(16.dp),
                    )
                } else {
                    // Only the list scrolls, and only when there are more computers than fit.
                    LazyColumn(Modifier.weight(1f, fill = false).clip(RoundedCornerShape(16.dp)).background(PmColors.Surface)) {
                        itemsIndexed(items, key = { _, it -> it.host.key }) { i, item ->
                            if (i > 0) RowDivider(startIndent = 68.dp)
                            HostRow(
                                item = item,
                                primary = i == 0,
                                enabled = idle,
                                onConnect = { onConnect(item.host) },
                                onOptions = if (item.saved) ({ optionsFor = item.host }) else null,
                            )
                        }
                    }
                }
            }
        }
    }
    val status: @Composable () -> Unit = {
        if (permissionDenied) Banner("PhoneInput needs microphone permission to stream. Allow it in the prompt or in system settings.", PmColors.Warn)
        stream.lastError?.let { Banner(it, PmColors.Warn) }
        if (!idle) {
            AccentButton("Streaming to ${stream.host?.name ?: "computer"}: open", onClick = onOpenLive, modifier = Modifier.fillMaxWidth())
        }
    }
    val actions: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlineButton(
                "Add",
                onClick = { adding = true },
                icon = PmIcons.Plus,
                enabled = idle,
                height = 52.dp,
                radius = 26.dp,
                modifier = Modifier.weight(1f),
            )
            AccentButton(
                if (qrBusy) "Connecting…" else "Scan QR",
                onClick = onScanQr,
                icon = PmIcons.Qr,
                enabled = idle && !qrBusy,
                height = 52.dp,
                modifier = Modifier.weight(1f),
            )
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight && maxHeight < 600.dp) {
            // Phones in landscape are too short for one column: controls scroll on the left, the list fills the right.
            Row(
                Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TopBar(onSettings)
                    Hero(stream, 0.dp, showHint = true)
                    status()
                    TransportSelector(mode, onSelect = { mode = it })
                    actions()
                }
                panel(Modifier.weight(1.2f).fillMaxHeight().padding(top = 12.dp))
            }
        } else {
            val compact = maxHeight < 700.dp
            val heroSize = when {
                maxHeight < 600.dp -> 0.dp
                compact -> 104.dp
                else -> 148.dp
            }
            Column(
                Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp),
            ) {
                TopBar(onSettings)
                Hero(stream, heroSize, showHint = !compact)
                status()
                TransportSelector(mode, onSelect = { mode = it })
                panel(Modifier.weight(1f))
                actions()
            }
        }
    }

    optionsFor?.let { host ->
        HostOptionsDialog(
            host = host,
            onEdit = {
                optionsFor = null
                editing = host
            },
            onRemove = {
                optionsFor = null
                settings.removeHost(host)
            },
            onDismiss = { optionsFor = null },
        )
    }
    editing?.let { host ->
        HostEditDialog(
            title = "Edit computer",
            initial = host,
            confirmLabel = "Save",
            onConfirm = { updated ->
                settings.updateHost(host, updated)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    if (adding) {
        HostEditDialog(
            title = "Add computer",
            initial = null,
            confirmLabel = "Connect",
            onConfirm = {
                adding = false
                settings.rememberHost(it)
                onConnect(it)
            },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun TopBar(onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(PmColors.Accent), contentAlignment = Alignment.Center) {
            Icon(PmIcons.Mic, contentDescription = null, tint = PmColors.OnAccent, modifier = Modifier.size(18.dp))
        }
        Text("PhoneInput", color = PmColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp).weight(1f))
        BarIconButton(PmIcons.Sliders, "Settings", onSettings)
    }
}

@Composable
private fun Hero(stream: StreamUiState, size: Dp, showHint: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (size > 0.dp) {
            Box(Modifier.size(size).dashedCircle(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(size * 0.76f).clip(CircleShape).background(PmColors.Surface2), contentAlignment = Alignment.Center) {
                    Icon(PmIcons.Mic, contentDescription = null, tint = PmColors.TextMuted, modifier = Modifier.size(size * 0.31f))
                }
            }
        }
        if (stream.link == LinkState.Idle) StatusChip("Not connected", PmColors.Warn, background = PmColors.Surface2)
        else StatusChip("Streaming", PmColors.Accent, background = PmColors.Surface2)
        if (showHint) {
            Text(
                "Pick a computer running PhoneInput, or add one by its address.",
                color = PmColors.TextMuted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 300.dp),
            )
        }
    }
}

private fun Modifier.dashedCircle(): Modifier = drawBehind {
    drawCircle(
        color = PmColors.BorderStrong,
        radius = size.minDimension / 2 - 1.dp.toPx(),
        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))),
    )
}

@Composable
private fun TransportSelector(mode: Transport, onSelect: (Transport) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PmColors.Surface).padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TransportOption("Wi-Fi", PmIcons.Wifi, selected = mode == Transport.WiFi, enabled = true) { onSelect(Transport.WiFi) }
        TransportOption("USB", PmIcons.Usb, selected = mode == Transport.Usb, enabled = true) { onSelect(Transport.Usb) }
        TransportOption("Bluetooth", PmIcons.Bluetooth, selected = mode == Transport.Bluetooth, enabled = true) { onSelect(Transport.Bluetooth) }
    }
}

/** USB mode: a short checklist and one Connect button. */
@Composable
private fun UsbPanel(modifier: Modifier, enabled: Boolean, onConnect: () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PmColors.Surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Connect with a USB cable", color = PmColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            UsbStep(1, "Turn on USB debugging: Settings › About phone › tap Build number 7 times, then Developer options › USB debugging.")
            UsbStep(2, "Plug the phone into the computer and tap Allow when asked.")
            UsbStep(3, "Keep PhoneInput open on the computer; it prepares the cable link by itself.")
        }
        AccentButton("Connect over USB", onClick = onConnect, icon = PmIcons.Usb, enabled = enabled, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun UsbStep(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(PmColors.AccentBg), contentAlignment = Alignment.Center) {
            Text("$n", color = PmColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(text, color = PmColors.TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun RowScope.TransportOption(label: String, icon: ImageVector, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .weight(1f)
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) PmColors.Border else Color.Transparent)
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
            .semantics { if (!enabled) stateDescription = "Coming soon" },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = when {
            selected -> PmColors.Text
            enabled -> PmColors.TextMuted
            else -> PmColors.TextMuted.copy(alpha = 0.6f)
        }
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun HostRow(item: HostItem, primary: Boolean, enabled: Boolean, onConnect: () -> Unit, onOptions: (() -> Unit)?) {
    val host = item.host
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = if (onOptions != null) 2.dp else 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box {
            IconTile(PmIcons.Monitor)
            if (item.online) {
                Box(Modifier.align(Alignment.TopEnd).size(10.dp).clip(CircleShape).background(PmColors.Surface).padding(2.dp).clip(CircleShape).background(PmColors.Accent))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(host.name, color = PmColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The green dot on the icon already says "on Wi-Fi now".
            val status = when {
                item.online && host.os != HostOs.Unknown -> host.os.label
                item.online -> "On Wi-Fi"
                else -> "Saved · not seen"
            }
            Text(status, color = if (item.online) PmColors.Accent else PmColors.TextMuted, fontSize = 12.sp, maxLines = 1)
            Text(
                if (host.port == PhoneInputProtocol.AUDIO_PORT) host.address else host.key,
                color = PmColors.TextMuted,
                fontSize = 12.sp,
                fontFamily = PmMono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (primary) AccentButton("Connect", onClick = onConnect, enabled = enabled, height = 40.dp, horizontalPadding = 14.dp)
        else OutlineButton("Connect", onClick = onConnect, enabled = enabled, height = 40.dp, horizontalPadding = 14.dp)
        if (onOptions != null) {
            RoundIconButton(PmIcons.More, "More options for ${host.name}", onOptions, 40.dp, Color.Transparent, PmColors.TextMuted, iconSize = 20.dp)
        }
    }
}

@Composable
private fun HostOptionsDialog(host: Host, onEdit: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var confirmRemove by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PmColors.Surface,
        title = { Text(host.name, color = PmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(host.key, color = PmColors.TextMuted, fontSize = 13.sp, fontFamily = PmMono)
                if (confirmRemove) {
                    Text(
                        "Remove ${host.name} from your saved computers? It will still appear when found on Wi-Fi.",
                        color = PmColors.Text,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    DialogAction(PmIcons.Pencil, "Rename or edit address", PmColors.Text, onEdit)
                    DialogAction(PmIcons.Trash, "Remove", PmColors.Danger) { confirmRemove = true }
                }
            }
        },
        confirmButton = {
            if (confirmRemove) {
                TextButton(onClick = onRemove) { Text("Remove", color = PmColors.Danger, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = PmColors.TextMuted) }
        },
    )
}

@Composable
private fun DialogAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Text(label, color = color, fontSize = 16.sp)
        }
    }
}

@Composable
private fun HostEditDialog(
    title: String,
    initial: Host?,
    confirmLabel: String,
    onConfirm: (Host) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var address by remember { mutableStateOf(initial?.let { if (it.port == PhoneInputProtocol.AUDIO_PORT) it.address else it.key }.orEmpty()) }
    val parsed = remember(address) { parseAddress(address) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = PmColors.Accent,
        focusedLabelColor = PmColors.Accent,
        cursorColor = PmColors.Accent,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PmColors.Surface,
        title = { Text(title, color = PmColors.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    colors = fieldColors,
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it.trim() },
                    label = { Text("Address") },
                    placeholder = { Text("192.168.1.24") },
                    supportingText = { Text("Shown in PhoneInput on the computer") },
                    singleLine = true,
                    isError = address.isNotEmpty() && parsed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = fieldColors,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val p = parsed ?: return@TextButton
                    val trimmed = name.trim()
                    onConfirm(
                        p.copy(
                            name = trimmed.ifEmpty { p.address },
                            customName = trimmed.isNotEmpty() && trimmed != p.address,
                            os = initial?.os ?: p.os,
                        ),
                    )
                },
                enabled = parsed != null,
            ) {
                Text(confirmLabel, color = if (parsed != null) PmColors.Accent else PmColors.TextMuted, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = PmColors.TextMuted) }
        },
    )
}

/** Accepts "host" or "host:port". */
internal fun parseAddress(input: String): Host? {
    if (input.isBlank() || input.any { it.isWhitespace() }) return null
    val colon = input.lastIndexOf(':')
    val (address, port) = if (colon > 0 && input.count { it == ':' } == 1) {
        input.substring(0, colon) to (input.substring(colon + 1).toIntOrNull() ?: return null)
    } else {
        input to PhoneInputProtocol.AUDIO_PORT
    }
    if (address.isEmpty() || port !in 1..65535) return null
    return Host(name = address, address = address, port = port, os = HostOs.Unknown)
}
