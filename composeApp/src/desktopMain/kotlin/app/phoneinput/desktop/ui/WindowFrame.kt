package app.phoneinput.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import app.phoneinput.ui.icons.PmIcons
import app.phoneinput.ui.theme.PmColors
import java.awt.Cursor
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit

/**
 * Custom window chrome for the undecorated main window: a title bar that drags the window
 * (double-click maximizes), tray / minimize / maximize / close buttons, and resize edges.
 *
 * Maximize sets the bounds to the screen's work area ourselves, because an undecorated
 * frame maximized by the OS covers the Windows taskbar.
 */
class WindowChrome(private val scope: FrameWindowScope) {
    var maximized by mutableStateOf(false)
        private set
    private var restoreBounds: Rectangle? = null

    fun toggleMaximize() {
        val w = scope.window
        if (maximized) {
            restoreBounds?.let { w.bounds = it }
            maximized = false
        } else {
            restoreBounds = w.bounds
            val config = w.graphicsConfiguration ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
            val b = config.bounds
            w.bounds = Rectangle(b.x + insets.left, b.y + insets.top, b.width - insets.left - insets.right, b.height - insets.top - insets.bottom)
            maximized = true
        }
    }

    fun minimize() {
        scope.window.extendedState = java.awt.Frame.ICONIFIED
    }

    /** Leaves maximized state when the user starts dragging the title bar. */
    internal fun restoreForDrag(pointer: Point) {
        if (!maximized) return
        val w = scope.window
        val old = w.bounds
        val target = restoreBounds ?: Rectangle(old.x, old.y, old.width * 2 / 3, old.height * 2 / 3)
        val ratio = (pointer.x - old.x).toFloat() / old.width
        w.bounds = Rectangle(pointer.x - (target.width * ratio).toInt(), old.y, target.width, target.height)
        maximized = false
    }

    internal val window get() = scope.window
}

@Composable
fun WindowFrame(
    chrome: WindowChrome,
    onHideToTray: (() -> Unit)?,
    onClose: () -> Unit,
    titleContent: @Composable RowScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(PmColors.Bg)) {
        Column(Modifier.fillMaxSize()) {
            TitleBar(chrome, onHideToTray, onClose, titleContent)
            Box(Modifier.fillMaxWidth().height(1.dp).background(PmColors.Border))
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
        if (!chrome.maximized) ResizeEdges(chrome)
    }
}

@Composable
private fun TitleBar(
    chrome: WindowChrome,
    onHideToTray: (() -> Unit)?,
    onClose: () -> Unit,
    titleContent: @Composable RowScope.() -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).fillMaxHeight().dragsWindow(chrome).padding(start = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            content = titleContent,
        )
        if (onHideToTray != null) CaptionButton(PmIcons.ToTray, "Hide to tray", onHideToTray)
        CaptionButton(PmIcons.Minimize, "Minimize", chrome::minimize)
        CaptionButton(if (chrome.maximized) PmIcons.Restore else PmIcons.Maximize, if (chrome.maximized) "Restore" else "Maximize", chrome::toggleMaximize)
        CaptionButton(PmIcons.Close, "Close", onClose, danger = true)
    }
}

@Composable
private fun CaptionButton(icon: ImageVector, label: String, onClick: () -> Unit, danger: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        hovered && danger -> Color(0xFFC42B1C)
        hovered -> PmColors.Surface2
        else -> Color.Transparent
    }
    Box(
        Modifier
            .width(46.dp)
            .fillMaxHeight()
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (hovered && danger) Color.White else PmColors.TextMuted, modifier = Modifier.size(16.dp))
    }
}

/** Moves the window with the pointer (absolute screen coordinates); double-click toggles maximize. */
private fun Modifier.dragsWindow(chrome: WindowChrome): Modifier = pointerInput(chrome) {
    var lastPress = 0L
    awaitPointerEventScope {
        while (true) {
            val down = awaitPointerEvent()
            if (down.type != PointerEventType.Press) continue
            val now = System.currentTimeMillis()
            if (now - lastPress < 400) {
                chrome.toggleMaximize()
                lastPress = 0L
                continue
            }
            lastPress = now
            val startPointer = MouseInfo.getPointerInfo()?.location ?: continue
            var startWindow = chrome.window.location
            var moved = false
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Release) break
                if (event.type == PointerEventType.Move) {
                    val p = MouseInfo.getPointerInfo()?.location ?: continue
                    if (!moved && (kotlin.math.abs(p.x - startPointer.x) + kotlin.math.abs(p.y - startPointer.y)) < 4) continue
                    if (!moved) {
                        moved = true
                        if (chrome.maximized) {
                            chrome.restoreForDrag(p)
                            startWindow = chrome.window.location
                        }
                    }
                    chrome.window.setLocation(startWindow.x + p.x - startPointer.x, startWindow.y + p.y - startPointer.y)
                }
            }
        }
    }
}

private enum class Edge(val cursor: Int, val left: Boolean = false, val right: Boolean = false, val top: Boolean = false, val bottom: Boolean = false) {
    Left(Cursor.W_RESIZE_CURSOR, left = true),
    Right(Cursor.E_RESIZE_CURSOR, right = true),
    Top(Cursor.N_RESIZE_CURSOR, top = true),
    Bottom(Cursor.S_RESIZE_CURSOR, bottom = true),
    TopLeft(Cursor.NW_RESIZE_CURSOR, left = true, top = true),
    TopRight(Cursor.NE_RESIZE_CURSOR, right = true, top = true),
    BottomLeft(Cursor.SW_RESIZE_CURSOR, left = true, bottom = true),
    BottomRight(Cursor.SE_RESIZE_CURSOR, right = true, bottom = true),
}

@Composable
private fun BoxScope.ResizeEdges(chrome: WindowChrome) {
    val t = 5.dp
    val c = 10.dp
    Box(Modifier.align(Alignment.CenterStart).width(t).fillMaxHeight().resizes(chrome, Edge.Left))
    Box(Modifier.align(Alignment.CenterEnd).width(t).fillMaxHeight().resizes(chrome, Edge.Right))
    Box(Modifier.align(Alignment.TopCenter).height(t).fillMaxWidth().resizes(chrome, Edge.Top))
    Box(Modifier.align(Alignment.BottomCenter).height(t).fillMaxWidth().resizes(chrome, Edge.Bottom))
    Box(Modifier.align(Alignment.TopStart).size(c).resizes(chrome, Edge.TopLeft))
    Box(Modifier.align(Alignment.TopEnd).size(c).resizes(chrome, Edge.TopRight))
    Box(Modifier.align(Alignment.BottomStart).size(c).resizes(chrome, Edge.BottomLeft))
    Box(Modifier.align(Alignment.BottomEnd).size(c).resizes(chrome, Edge.BottomRight))
}

private fun Modifier.resizes(chrome: WindowChrome, edge: Edge): Modifier =
    pointerHoverIcon(PointerIcon(Cursor(edge.cursor))).pointerInput(edge) {
        awaitPointerEventScope {
            while (true) {
                val down = awaitPointerEvent()
                if (down.type != PointerEventType.Press) continue
                val start = MouseInfo.getPointerInfo()?.location ?: continue
                val startBounds = chrome.window.bounds
                val min = chrome.window.minimumSize
                while (true) {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                    if (event.type == PointerEventType.Release) break
                    val p = MouseInfo.getPointerInfo()?.location ?: continue
                    val dx = p.x - start.x
                    val dy = p.y - start.y
                    var x = startBounds.x
                    var y = startBounds.y
                    var w = startBounds.width
                    var h = startBounds.height
                    if (edge.right) w = maxOf(min.width, startBounds.width + dx)
                    if (edge.bottom) h = maxOf(min.height, startBounds.height + dy)
                    if (edge.left) {
                        w = maxOf(min.width, startBounds.width - dx)
                        x = startBounds.x + startBounds.width - w
                    }
                    if (edge.top) {
                        h = maxOf(min.height, startBounds.height - dy)
                        y = startBounds.y + startBounds.height - h
                    }
                    chrome.window.setBounds(x, y, w, h)
                }
            }
        }
    }

@Composable
fun TitleText(text: String) {
    Text(text, color = PmColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}
