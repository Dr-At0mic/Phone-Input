package app.phoneinput.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.phoneinput.desktop.DesktopState
import app.phoneinput.desktop.net.BluetoothStatus
import app.phoneinput.desktop.net.UsbDevice
import app.phoneinput.desktop.net.UsbStatus
import app.phoneinput.ui.components.AccentButton
import app.phoneinput.ui.components.OutlineButton
import app.phoneinput.ui.components.PmCard
import app.phoneinput.ui.icons.PmIcons
import app.phoneinput.ui.theme.PmColors
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Dark-on-white QR code with a quiet zone, drawn as vector cells so it stays sharp at any size. */
@Composable
fun QrCode(content: String, size: Dp, modifier: Modifier = Modifier) {
    val matrix = remember(content) {
        QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(size * 0.07f)
            .semantics { contentDescription = "QR code: scan it with PhoneInput on your phone to connect" },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val n = matrix.width
            val cell = this.size.width / n
            for (y in 0 until n) for (x in 0 until n) {
                if (matrix[x, y]) drawRect(Color(0xFF0B0E13), Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
            }
        }
    }
}

/** The "Scan to connect" card used on the empty state. */
@Composable
fun QrCard(state: DesktopState, modifier: Modifier = Modifier) {
    PmCard(modifier) {
        Text("Scan to connect", color = PmColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        val uri = state.pairingUri
        if (uri == null) {
            Text("Connect this computer to Wi-Fi to show a QR code.", color = PmColors.Warn, fontSize = 13.sp)
        } else {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(uri, 184.dp) }
            Text(
                "In PhoneInput on your phone, tap QR and point the camera here.",
                color = PmColors.TextMuted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Full-window overlay with a large QR code. */
@Composable
fun QrOverlay(state: DesktopState, onClose: () -> Unit) {
    val uri = state.pairingUri ?: return
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(PmColors.Surface)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(28.dp)
                .widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Scan with PhoneInput", color = PmColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            QrCode(uri, 300.dp)
            Text(
                "Open PhoneInput on your phone or tablet, tap QR, and point the camera at this code. It connects to ${state.hostName} right away.",
                color = PmColors.TextMuted,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center,
            )
            OutlineButton("Close", onClick = onClose, height = 40.dp, radius = 10.dp)
        }
    }
}

/** USB status and the one action it may need. */
@Composable
fun UsbCard(usb: UsbStatus, onInstallAdb: () -> Unit, modifier: Modifier = Modifier) {
    PmCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(PmIcons.Usb, contentDescription = null, tint = PmColors.Text, modifier = Modifier.size(18.dp))
            Text("USB", color = PmColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        val cable = usb.devices.filter { !it.wireless }
        when {
            usb.installing -> {
                Text("Downloading Android platform-tools from Google…", color = PmColors.Text, fontSize = 13.sp)
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = PmColors.Accent, trackColor = PmColors.Border)
            }
            !usb.adbFound -> {
                Text(
                    "USB connections use Android's adb tool, which isn't on this computer yet.",
                    color = PmColors.TextMuted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
                usb.error?.let { Text(it, color = PmColors.Danger, fontSize = 13.sp) }
                AccentButton("Set up USB", onClick = onInstallAdb, height = 38.dp)
                Text("Downloads Google's platform-tools (about 7 MB).", color = PmColors.TextMuted, fontSize = 12.sp)
            }
            cable.isEmpty() -> UsbHint("Plug in your phone with a cable and turn on USB debugging (Settings › Developer options).")
            else -> cable.forEach { UsbDeviceRow(it) }
        }
    }
}

@Composable
private fun UsbHint(text: String) {
    Text(text, color = PmColors.TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
}

@Composable
private fun UsbDeviceRow(d: UsbDevice) {
    val (text, color) = when (d.state) {
        UsbDevice.State.Ready -> "Ready: choose USB in the phone app" to PmColors.Accent
        UsbDevice.State.Unauthorized -> "Tap Allow on the phone's USB debugging prompt" to PmColors.Warn
        UsbDevice.State.Offline -> "Not responding, replug the cable" to PmColors.Warn
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Column {
            Text(d.model, color = PmColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(text, color = color, fontSize = 12.sp)
        }
    }
}

/** Bluetooth status: phones paired with this computer connect over RFCOMM. */
@Composable
fun BluetoothCard(bt: BluetoothStatus, modifier: Modifier = Modifier) {
    PmCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(PmIcons.Bluetooth, contentDescription = null, tint = PmColors.Text, modifier = Modifier.size(18.dp))
            Text("Bluetooth", color = PmColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        when {
            !bt.supported -> UsbHint("Bluetooth connections aren't supported on this system.")
            bt.listening -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(PmColors.Accent))
                    Text("Ready: choose Bluetooth in the phone app", color = PmColors.Accent, fontSize = 12.sp)
                }
                UsbHint("Pair the phone with this computer first (in the computer's or the phone's Bluetooth settings).")
            }
            else -> {
                Text(bt.error ?: "Starting…", color = PmColors.Warn, fontSize = 13.sp, lineHeight = 19.sp)
                UsbHint("PhoneInput keeps checking; turn Bluetooth on and it becomes ready by itself.")
            }
        }
    }
}
