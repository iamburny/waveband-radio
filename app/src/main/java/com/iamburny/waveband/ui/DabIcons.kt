package com.iamburny.waveband.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The design's 24-unit line and solid icons. They are drawn black and tinted by [androidx.compose.material3.Icon]. */
object DabIcons {

    private class Layer(val d: String, val stroke: Float = 0f, val fill: Boolean = false)

    private fun icon(name: String, vararg layers: Layer): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (layer in layers) {
            builder.addPath(
                pathData = addPathNodes(layer.d),
                fill = if (layer.fill) SolidColor(Color.Black) else null,
                stroke = if (layer.stroke > 0f) SolidColor(Color.Black) else null,
                strokeLineWidth = layer.stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    private fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

    private const val HEART = "M12 20.5s-7.5-4.6-7.5-10.3A4.2 4.2 0 0 1 12 7.6a4.2 4.2 0 0 1 7.5 2.6c0 5.7-7.5 10.3-7.5 10.3z"

    val Playing = icon(
        "Playing",
        Layer(circle(12f, 12f, 2.5f), stroke = 2f),
        Layer("M7.8 7.8a6 6 0 0 0 0 8.4M16.2 7.8a6 6 0 0 1 0 8.4M4.9 4.9a10 10 0 0 0 0 14.2M19.1 4.9a10 10 0 0 1 0 14.2", stroke = 2f),
    )
    val Stations = icon("Stations", Layer("M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01", stroke = 2f))
    val Presets = icon(
        "Presets",
        Layer(
            roundRect(3f, 3f, 7.5f, 7.5f, 1.5f) + roundRect(13.5f, 3f, 7.5f, 7.5f, 1.5f) +
                roundRect(3f, 13.5f, 7.5f, 7.5f, 1.5f) + roundRect(13.5f, 13.5f, 7.5f, 7.5f, 1.5f),
            stroke = 2f,
        ),
    )
    val Settings = icon(
        "Settings",
        Layer("M4 7h10M18 7h2M4 17h4M12 17h8", stroke = 2f),
        Layer(circle(16f, 7f, 2f) + circle(10f, 17f, 2f), stroke = 2f),
    )
    val HeartFilled = icon("HeartFilled", Layer(HEART, fill = true))
    val HeartOutline = icon("HeartOutline", Layer(HEART, stroke = 2f))
    val Previous = icon(
        "Previous",
        Layer(roundRect(4f, 5f, 2.6f, 14f, 1f), fill = true),
        Layer("M20 5.5v13a.8.8 0 0 1-1.2.7L8.6 12.7a.8.8 0 0 1 0-1.4l10.2-6.5a.8.8 0 0 1 1.2.7z", fill = true),
    )
    val Next = icon(
        "Next",
        Layer(roundRect(17.4f, 5f, 2.6f, 14f, 1f), fill = true),
        Layer("M4 5.5v13a.8.8 0 0 0 1.2.7l10.2-6.5a.8.8 0 0 0 0-1.4L5.2 4.8A.8.8 0 0 0 4 5.5z", fill = true),
    )
    val SeekDown = icon("SeekDown", Layer("M11.5 6.5v11L3.5 12zM20.5 6.5v11l-8-5.5z", fill = true))
    val SeekUp = icon("SeekUp", Layer("M12.5 6.5v11l8-5.5zM3.5 6.5v11l8-5.5z", fill = true))
    val Pause = icon("Pause", Layer(roundRect(6f, 5f, 4f, 14f, 1.2f) + roundRect(14f, 5f, 4f, 14f, 1.2f), fill = true))
    val Play = icon("Play", Layer("M7 5.5v13a.8.8 0 0 0 1.2.7l10.2-6.5a.8.8 0 0 0 0-1.4L8.2 4.8A.8.8 0 0 0 7 5.5z", fill = true))
    val Chevron = icon("Chevron", Layer("M9 5l7 7-7 7", stroke = 2.4f))
    val Back = icon("Back", Layer("M15 5l-7 7 7 7", stroke = 2.4f))
    val Close = icon("Close", Layer("M6 6l12 12M18 6L6 18", stroke = 2.4f))
    val Search = icon("Search", Layer(circle(10.5f, 10.5f, 6.5f) + "M15.5 15.5L20.5 20.5", stroke = 2.4f))
    val Check = icon("Check", Layer("M5 12.5l4.5 4.5L19 7.5", stroke = 2.6f))
}
