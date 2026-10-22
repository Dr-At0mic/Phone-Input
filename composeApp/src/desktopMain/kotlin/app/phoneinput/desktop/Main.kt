package app.phoneinput.desktop

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.phoneinput.desktop.ui.DesktopRoot
import app.phoneinput.desktop.ui.TitleStatus
import app.phoneinput.desktop.ui.TitleText
import app.phoneinput.desktop.ui.WindowChrome
import app.phoneinput.desktop.ui.WindowFrame
import app.phoneinput.ui.icons.PmIcons
import app.phoneinput.ui.theme.PhoneInputTheme
import java.awt.Dimension
import java.awt.SystemTray
import javax.swing.JOptionPane
import kotlin.system.exitProcess

fun main() {
    val controller = DesktopController()
    if (!controller.bindPorts()) {
        JOptionPane.showMessageDialog(
            null,
            "PhoneInput is already running. Look for its icon in the system tray.\n\n" +
                "If it isn't there, another program is using ports 5823–5825.",
            "PhoneInput",
            JOptionPane.INFORMATION_MESSAGE,
        )
        exitProcess(1)
    }
    controller.start()
    runApp(controller)
}

private val digitKeys = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)

private fun runApp(controller: DesktopController) = application {
    DisposableEffect(Unit) { onDispose { controller.close() } }

    val state by controller.state.collectAsState()
    var windowVisible by remember { mutableStateOf(true) }
    val appIcon = rememberVectorPainter(PmIcons.AppMark)
    val traySupported = remember { SystemTray.isSupported() }
    val windowState = rememberWindowState(width = 1120.dp, height = 740.dp, position = WindowPosition(Alignment.Center))

    val quit = {
        controller.close()
        exitApplication()
    }

    if (traySupported) {
        val active = state.active
        Tray(
            icon = appIcon,
            tooltip = when {
                active == null -> "PhoneInput: waiting for a device"
                state.muted -> "PhoneInput: ${active.name} (muted)"
                else -> "PhoneInput: ${active.name} (live)"
            },
            onAction = { windowVisible = true },
            menu = {
                // Quick device switching straight from the tray.
                state.connected.forEach { d ->
                    CheckboxItem(d.name, checked = d.active, onCheckedChange = { controller.select(d.sessionId) })
                }
                if (state.connected.isNotEmpty()) Separator()
                Item(if (state.muted) "Unmute" else "Mute", onClick = { controller.setMuted(!state.muted) })
                Item("Show PhoneInput", onClick = { windowVisible = true })
                Separator()
                Item("Quit", onClick = quit)
            },
        )
    }

    Window(
        onCloseRequest = quit,
        visible = windowVisible,
        title = "PhoneInput",
        icon = appIcon,
        state = windowState,
        undecorated = true,
        onKeyEvent = { e ->
            if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) return@Window false
            when (val i = digitKeys.indexOf(e.key)) {
                -1 -> if (e.key == Key.M) {
                    controller.setMuted(!controller.state.value.muted)
                    true
                } else {
                    false
                }
                else -> {
                    controller.selectIndex(i)
                    true
                }
            }
        },
    ) {
        window.minimumSize = Dimension(640, 520)
        val chrome = remember { WindowChrome(this) }
        PhoneInputTheme {
            WindowFrame(
                chrome = chrome,
                onHideToTray = if (traySupported) ({ windowVisible = false }) else null,
                onClose = quit,
                titleContent = {
                    Icon(PmIcons.AppMark, contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(22.dp))
                    TitleText("PhoneInput")
                    Spacer(Modifier.size(6.dp))
                    TitleStatus(state)
                },
            ) {
                DesktopRoot(state, controller)
            }
        }
    }
}
