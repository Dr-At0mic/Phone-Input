package app.phoneinput.android.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.phoneinput.android.Host
import app.phoneinput.ui.components.AccentButton
import app.phoneinput.ui.components.IconTile
import app.phoneinput.ui.components.OutlineButton
import app.phoneinput.ui.components.RowDivider
import app.phoneinput.ui.icons.PmIcons
import app.phoneinput.ui.theme.PmColors
import app.phoneinput.ui.theme.PmMono

/**
 * Bluetooth mode: the computers this phone is paired with. Pairing itself happens in the system
 * Bluetooth settings; the list refreshes when Bluetooth or a pairing changes.
 */
@Composable
fun BluetoothPanel(modifier: Modifier, enabled: Boolean, onConnect: (Host) -> Unit) {
    val context = LocalContext.current
    val adapter = remember { context.getSystemService(BluetoothManager::class.java)?.adapter }
    var hasPermission by remember { mutableStateOf(hasConnectPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }

    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                refresh++
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val bluetoothOn = remember(refresh, hasPermission) { adapter?.isEnabled == true }
    val computers = remember(refresh, hasPermission, bluetoothOn) { if (hasPermission && bluetoothOn) pairedComputers(adapter) else emptyList() }

    Column(
        // The whole panel scrolls, so an error banner above it can't squeeze the list or buttons away.
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PmColors.Surface).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Connect over Bluetooth", color = PmColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        when {
            adapter == null -> Hint("This phone doesn't have Bluetooth.")
            !hasPermission -> {
                Hint("PhoneInput needs access to nearby devices to see the computers paired with this phone.")
                AccentButton(
                    "Allow Bluetooth access",
                    onClick = { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                    icon = PmIcons.Bluetooth,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            !bluetoothOn -> {
                Hint("Bluetooth is off.")
                AccentButton(
                    "Turn on Bluetooth",
                    onClick = { runCatching { context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } },
                    icon = PmIcons.Bluetooth,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            computers.isEmpty() -> {
                UsbStep(1, "Pair this phone with the computer in Bluetooth settings (on either device).")
                UsbStep(2, "Keep PhoneInput open on the computer, then come back here.")
                OpenSettingsButton(context)
            }
            else -> {
                computers.forEachIndexed { i, host ->
                    if (i > 0) RowDivider(startIndent = 56.dp)
                    PairedRow(host, primary = i == 0, enabled = enabled, onConnect = { onConnect(host) })
                }
                Hint("Not listed? Pair it first, and keep PhoneInput open on the computer.")
                OpenSettingsButton(context)
            }
        }
    }
}

@Composable
private fun PairedRow(host: Host, primary: Boolean, enabled: Boolean, onConnect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconTile(PmIcons.Monitor)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(host.name, color = PmColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(host.address, color = PmColors.TextMuted, fontSize = 12.sp, fontFamily = PmMono, maxLines = 1)
        }
        if (primary) AccentButton("Connect", onClick = onConnect, enabled = enabled, height = 40.dp, horizontalPadding = 14.dp)
        else OutlineButton("Connect", onClick = onConnect, enabled = enabled, height = 40.dp, horizontalPadding = 14.dp)
    }
}

@Composable
private fun OpenSettingsButton(context: Context) {
    Box(Modifier.fillMaxWidth()) {
        OutlineButton(
            "Bluetooth settings",
            onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } },
            icon = PmIcons.Bluetooth,
            height = 44.dp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = PmColors.TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
}

fun hasConnectPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

/** Paired computers first; if none report the "computer" class (some do not), every paired device. */
@SuppressLint("MissingPermission") // Only called once BLUETOOTH_CONNECT is granted.
private fun pairedComputers(adapter: BluetoothAdapter?): List<Host> {
    val bonded = runCatching { adapter?.bondedDevices.orEmpty().toList() }.getOrDefault(emptyList())
    val computers = bonded.filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER }
    return computers.ifEmpty { bonded }
        .map { Host.bluetooth(it.name?.takeIf(String::isNotBlank) ?: it.address, it.address) }
        .sortedBy { it.name.lowercase() }
}
