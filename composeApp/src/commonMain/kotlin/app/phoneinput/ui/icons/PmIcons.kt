package app.phoneinput.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import app.phoneinput.ui.theme.PmColors

/** Outline icons (2px stroke, 24px grid) matching the mockups. Tinted by `Icon`. */
object PmIcons {
    private val MicPaths = arrayOf(
        "M12 2a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z",
        "M19 10v1a7 7 0 0 1-14 0v-1",
        "M12 18v4",
    )

    val Mic = stroke("Mic", *MicPaths)
    val MicOff = stroke(
        "MicOff",
        "M2 2l20 20",
        "M9 9v2a3 3 0 0 0 5.1 2.1",
        "M15 9.3V5a3 3 0 0 0-5.9-.7",
        "M19 10v1a7 7 0 0 1-.9 3.4",
        "M5 10v1a7 7 0 0 0 11.6 5.3",
        "M12 18v4",
    )
    val Sliders = stroke(
        "Sliders",
        "M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12",
        circle(16f, 6f, 2f),
        circle(10f, 12f, 2f),
        circle(18f, 18f, 2f),
    )
    val Wifi = stroke(
        "Wifi",
        "M2 9a15 15 0 0 1 20 0",
        "M5 12.5a10 10 0 0 1 14 0",
        "M8.5 16a5 5 0 0 1 7 0",
        "M12 19.5h.01",
    )
    val Usb = stroke(
        "Usb",
        circle(12f, 20f, 2f),
        "M12 18V3",
        "M9 6l3-3 3 3",
        "M12 14l-5-3V8",
        "M12 12l5-3V7",
    )
    val Speaker = stroke("Speaker", "M11 5L6 9H3v6h3l5 4V5z", "M15.5 8.5a5 5 0 0 1 0 7", "M18.5 5.5a9 9 0 0 1 0 13")
    val Bluetooth = stroke("Bluetooth", "M7 7l10 10-5 5V2l5 5L7 17")
    val Qr = stroke(
        "Qr",
        "M3 3h7v7H3z",
        "M14 3h7v7h-7z",
        "M3 14h7v7H3z",
        "M14 14h3v3h-3zM20 14v.01M14 20h.01M17 20h4v-3",
    )
    val Monitor = stroke(
        "Monitor",
        "M5 4h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
        "M8 20h8M12 16v4",
    )
    val Phone = stroke(
        "Phone",
        "M9 2h6a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z",
        "M11 18h2",
    )
    val Back = stroke("Back", "M15 6l-6 6 6 6")
    val Chevron = stroke("Chevron", "M9 6l6 6-6 6")
    val Plus = stroke("Plus", "M12 5v14M5 12h14")
    val Swap = stroke("Swap", "M17 3l4 4-4 4", "M3 7h18", "M7 21l-4-4 4-4", "M21 17H3")
    val Check = stroke("Check", "M5 12l5 5L20 7")
    val Alert = stroke(
        "Alert",
        "M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z",
        "M12 9v4",
        "M12 17h.01",
    )
    val Minimize = stroke("Minimize", "M6 12h12")
    val Maximize = stroke("Maximize", "M6 6h12v12H6z")
    val Restore = stroke("Restore", "M6 9h9v9H6z", "M9 6h9v9")
    val Close = stroke("Close", "M7 7l10 10M17 7L7 17")
    val ToTray = stroke("ToTray", "M12 4v10", "M8 10l4 4 4-4", "M4 19h16")
    val Pencil = stroke("Pencil", "M4 20h4L19 9l-4-4L4 16z", "M13.5 6.5l4 4")
    val Trash = stroke("Trash", "M4 7h16", "M10 11v6M14 11v6", "M6 7l1 13h10l1-13", "M9 7V4h6v3")
    val More = stroke("More", "M12 5h.01M12 12h.01M12 19h.01")
    val Link = stroke(
        "Link",
        "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1",
        "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1",
    )
    val Stop = ImageVector.Builder("Stop", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            addPathNodes("M8 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z"),
            fill = SolidColor(Color.Black),
        )
    }.build()

    /** Full-color app mark (accent tile + mic) for the window and tray; not meant to be tinted. */
    val AppMark = ImageVector.Builder("AppMark", 32.dp, 32.dp, 24f, 24f).apply {
        addPath(
            addPathNodes("M6 0h12a6 6 0 0 1 6 6v12a6 6 0 0 1-6 6H6a6 6 0 0 1-6-6V6a6 6 0 0 1 6-6z"),
            fill = SolidColor(PmColors.Accent),
        )
        addGroup(scaleX = 0.66f, scaleY = 0.66f, pivotX = 12f, pivotY = 12f)
        MicPaths.forEach {
            addPath(
                addPathNodes(it),
                stroke = SolidColor(PmColors.OnAccent),
                strokeLineWidth = 2.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        clearGroup()
    }.build()

    private fun circle(cx: Float, cy: Float, r: Float): String =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    private fun stroke(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    addPathNodes(it),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
