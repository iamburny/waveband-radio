package com.iamburny.waveband.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iamburny.waveband.Monogram
import com.iamburny.waveband.backend.DabServiceInfo
import com.iamburny.waveband.logos.RadioDns
import com.iamburny.waveband.logos.StationLogos
import com.iamburny.waveband.ui.theme.Dab
import com.iamburny.waveband.ui.theme.OnAccent
import com.iamburny.waveband.ui.theme.dabText

/** Source of downloaded station logos; null where logos are not available, such as in previews. */
val LocalStationLogos = staticCompositionLocalOf<StationLogos?> { null }

/** The station's downloaded logo once it has loaded, or null if there is none. */
@Composable
fun rememberStationLogo(info: DabServiceInfo): ImageBitmap? {
    val logos = LocalStationLogos.current ?: return null
    val available by logos.available.collectAsStateWithLifecycle()
    val key = remember(info) { RadioDns.logoKey(info) }
    val version = key?.let { available[it] }
    // Starts from the memory cache, so a tile that is recreated shows its logo without a flash of the monogram.
    val cached = remember(key, version) { if (key != null && version != null) logos.cachedBitmap(key, version)?.asImageBitmap() else null }
    val image by produceState(cached, key, version) {
        value = if (key != null && version != null) logos.bitmap(key, version)?.asImageBitmap() else null
    }
    return image
}

/** Station tile: the broadcaster's logo when one has been downloaded, else the generated monogram. */
@Composable
fun StationTile(info: DabServiceInfo, size: Dp, corner: Dp, fontSize: Int, modifier: Modifier = Modifier) {
    val logo = rememberStationLogo(info)
    if (logo != null) {
        Image(
            logo,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size).clip(RoundedCornerShape(corner)).background(Dab.colors.placeholder),
        )
    } else {
        MonogramTile(info.serviceId, info.label, size, corner, fontSize, modifier)
    }
}

/** Generated station logo tile. */
@Composable
fun MonogramTile(serviceId: Long, label: String, size: Dp, corner: Dp, fontSize: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Color(Monogram.colour(serviceId))),
        contentAlignment = Alignment.Center,
    ) {
        Text(Monogram.initials(label), style = dabText(fontSize, FontWeight.ExtraBold), color = Color.White, maxLines = 1)
    }
}

/** Optional border around [shape]: solid, or dashed like the design's empty preset tiles. */
fun Modifier.dabBorder(width: Dp, color: Color?, shape: Shape): Modifier =
    if (color == null) this else border(width, color, shape)

fun Modifier.dashedBorder(width: Dp, color: Color, corner: Dp): Modifier = drawBehind {
    val stroke = width.toPx()
    val inset = stroke / 2
    drawRoundRect(
        color = color,
        topLeft = Offset(inset, inset),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(corner.toPx() - inset),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(stroke * 3, stroke * 3))),
    )
}

/** Click and long-click with the design's rounded ripple; [onLongClick] is optional. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pressable(
    shape: Shape,
    onClickLabel: String? = null,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = this
    .clip(shape)
    .combinedClickable(
        enabled = enabled,
        onClickLabel = onClickLabel,
        onLongClickLabel = onLongClickLabel,
        onLongClick = onLongClick,
        role = Role.Button,
        indication = ripple(),
        interactionSource = null,
        onClick = onClick,
    )

/** Round or rounded-square icon button with a content description. */
@Composable
fun DabIconButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    iconSize: Dp,
    background: Color,
    tint: Color,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(background)
            .pressable(shape, enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) tint else tint.copy(alpha = 0.4f), modifier = Modifier.size(iconSize))
    }
}

/** Pill-shaped text button: filled with [background], or outlined when [outline] is set. */
@Composable
fun PillButton(
    text: String,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
    horizontalPadding: Dp = 26.dp,
    fontSize: Int = 22,
    weight: FontWeight = FontWeight.Bold,
    corner: Dp? = null,
    outline: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = corner?.let { RoundedCornerShape(it) } ?: CircleShape
    Box(
        modifier
            .height(height)
            .clip(shape)
            .background(if (enabled) background else background.copy(alpha = background.alpha * 0.5f))
            .dabBorder(3.dp, outline, shape)
            .pressable(shape, enabled = enabled, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = dabText(fontSize, weight),
            color = if (enabled) foreground else foreground.copy(alpha = 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The design's 104×64 switch. */
@Composable
fun DabSwitch(checked: Boolean, label: String, onCheckedChange: (Boolean) -> Unit) {
    val c = Dab.colors
    Row(
        Modifier
            .size(width = 104.dp, height = 64.dp)
            .clip(CircleShape)
            .background(if (checked) c.accent else c.track)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics { contentDescription = label }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (checked) Arrangement.End else Arrangement.Start,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(if (checked) OnAccent else c.knobOff))
    }
}

/** Signal strength as four rising bars, [bars] of them lit. */
@Composable
fun SignalBars(bars: Int, modifier: Modifier = Modifier) {
    val c = Dab.colors
    val description = when (bars) {
        0 -> "No signal"
        1 -> "Signal weak"
        2 -> "Signal fair"
        3 -> "Signal good"
        else -> "Signal strong"
    }
    Canvas(modifier.size(40.dp, 32.dp).semantics { contentDescription = description }) {
        val unit = size.width / 40f
        val tops = floatArrayOf(22f, 16f, 9f, 2f)
        for (i in 0 until 4) {
            drawRoundRect(
                color = if (i < bars) c.text else c.signalOff,
                topLeft = Offset((2f + i * 10f) * unit, tops[i] * unit),
                size = Size(6f * unit, (30f - tops[i]) * unit),
                cornerRadius = CornerRadius(1.5f * unit),
            )
        }
    }
}

@Composable
fun ScreenTitle(text: String) {
    Text(text, style = dabText(40, FontWeight.ExtraBold, letterSpacing = (-1).sp), color = Dab.colors.text, maxLines = 1)
}

@Composable
fun HSpace(width: Dp) = Spacer(Modifier.width(width))

@Composable
fun VSpace(height: Dp) = Spacer(Modifier.height(height))
