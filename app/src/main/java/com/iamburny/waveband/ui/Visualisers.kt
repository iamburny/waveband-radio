package com.iamburny.waveband.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.iamburny.waveband.backend.DabServiceInfo
import com.iamburny.waveband.data.Visualiser
import com.iamburny.waveband.playback.BandSmoother
import com.iamburny.waveband.playback.ScopeTrigger
import com.iamburny.waveband.playback.SpectrumAnalyser
import com.iamburny.waveband.playback.SpectrumTap
import com.iamburny.waveband.playback.VuMeter
import com.iamburny.waveband.ui.theme.Dab
import com.iamburny.waveband.ui.theme.DabColors
import com.iamburny.waveband.ui.theme.dabText
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The audio playing through [tap], drawn as [kind]; [logo] is the station's, for the radial view. */
@Composable
fun VisualiserView(kind: Visualiser, tap: SpectrumTap, service: DabServiceInfo, logo: ImageBitmap?, modifier: Modifier = Modifier) {
    when (kind) {
        Visualiser.ARTWORK -> Unit
        Visualiser.BARS -> SpectrumBars(tap, modifier.padding(16.dp))
        Visualiser.SCOPE -> Oscilloscope(tap, modifier.padding(16.dp))
        Visualiser.VU -> VuMeters(tap, modifier.padding(16.dp))
        Visualiser.RADIAL -> RadialSpectrum(tap, service, logo, modifier)
        Visualiser.WATERFALL -> Waterfall(tap, modifier)
    }
}

/**
 * Attaches to [tap] and calls [step] once per display frame with the seconds since the last one.
 * While [step] returns false, nothing is moving, so it is only called a few times a second. The
 * returned frame time changes whenever there is something new to draw; read it while drawing to
 * redraw. The tap is only fed while the screen is visible.
 */
@Composable
private fun rememberAudioFrames(tap: SpectrumTap, step: (dt: Float) -> Boolean): State<Long> {
    LifecycleStartEffect(tap) {
        tap.attach()
        onStopOrDispose { tap.detach() }
    }
    val frame = remember { mutableLongStateOf(0L) }
    val currentStep by rememberUpdatedState(step)
    LaunchedEffect(tap) {
        var last = 0L
        var moving = true
        while (true) {
            moving = withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
                last = now
                val wasMoving = moving
                currentStep(dt).also { if (it || wasMoving) frame.longValue = now }
            }
            if (!moving) {
                last = 0L
                delay(IDLE_POLL_MS)
            }
        }
    }
    return frame
}

/** Bars rising and falling with each frequency band, with falling peak caps. */
@Composable
private fun SpectrumBars(tap: SpectrumTap, modifier: Modifier) {
    val c = Dab.colors
    val analyser = remember { SpectrumAnalyser() }
    val smoother = remember { BandSmoother(analyser.bands) }
    val samples = remember { FloatArray(analyser.fftSize) }
    val target = remember { FloatArray(analyser.bands) }
    val frame by rememberAudioFrames(tap) { dt ->
        val rate = tap.read(samples)
        if (rate > 0) analyser.analyse(samples, rate, target) else target.fill(0f)
        smoother.step(target, dt)
        rate > 0 || !smoother.settled
    }

    Canvas(modifier) {
        if (frame == Long.MIN_VALUE) return@Canvas
        val levels = smoother.levels
        val peaks = smoother.peaks
        val count = levels.size
        val gap = size.width / count * 0.28f
        val barWidth = size.width / count - gap
        val inset = 12.dp.toPx()
        val height = size.height - 2 * inset
        val cap = 4.dp.toPx()
        val corner = CornerRadius(barWidth / 3, barWidth / 3)
        val brush = Brush.verticalGradient(listOf(c.accent, c.accent.copy(alpha = 0.55f)), startY = inset, endY = size.height - inset)
        for (b in 0 until count) {
            val x = gap / 2 + b * (barWidth + gap)
            val h = max(barWidth / 2, height * levels[b])
            drawRoundRect(brush, Offset(x, size.height - inset - h), Size(barWidth, h), corner)
            val peakY = size.height - inset - height * peaks[b] - cap * 2
            if (peaks[b] > 0.02f) drawRoundRect(c.text.copy(alpha = 0.8f), Offset(x, peakY), Size(barWidth, cap), CornerRadius(cap / 2, cap / 2))
        }
    }
}

/** The waveform itself, started at the same point of the wave each frame and scaled to fit. */
@Composable
private fun Oscilloscope(tap: SpectrumTap, modifier: Modifier) {
    val c = Dab.colors
    val samples = remember { FloatArray(SCOPE_SPAN * 2) }
    val trace = remember { FloatArray(SCOPE_SPAN) }
    val gain = remember { mutableFloatStateOf(1f) }
    val frame by rememberAudioFrames(tap) { dt ->
        val rate = tap.read(samples)
        if (rate > 0) {
            val start = ScopeTrigger.find(samples, SCOPE_SPAN)
            samples.copyInto(trace, 0, start, start + SCOPE_SPAN)
            // Follow loud passages at once and quiet ones gradually, so the trace keeps its size.
            var top = 0f
            for (s in trace) top = max(top, abs(s))
            val wanted = SCOPE_FILL / max(top, SCOPE_MIN_PEAK)
            gain.floatValue = if (wanted < gain.floatValue) wanted else gain.floatValue + (wanted - gain.floatValue) * min(1f, dt * 2f)
            true
        } else {
            val moving = trace.any { it != 0f }
            trace.fill(0f)
            moving
        }
    }
    val path = remember { Path() }

    Canvas(modifier) {
        if (frame == Long.MIN_VALUE) return@Canvas
        val grid = c.text.copy(alpha = 0.07f)
        for (i in 1 until 8) drawLine(grid, Offset(size.width * i / 8, 0f), Offset(size.width * i / 8, size.height), 1.dp.toPx())
        for (i in 1 until 4) drawLine(grid, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), 1.dp.toPx())
        drawLine(c.text.copy(alpha = 0.18f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())

        val mid = size.height / 2
        val scale = gain.floatValue * mid
        path.reset()
        for (i in 0 until SCOPE_SPAN step SCOPE_STRIDE) {
            val x = size.width * i / (SCOPE_SPAN - SCOPE_STRIDE)
            val y = mid - (trace[i] * scale).coerceIn(-mid, mid)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, c.accent.copy(alpha = 0.18f), style = Stroke(12.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, c.accent, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A pair of needle meters for the left and right channels, each with a peak light. */
@Composable
private fun VuMeters(tap: SpectrumTap, modifier: Modifier) {
    val left = remember { FloatArray(VU_WINDOW) }
    val right = remember { FloatArray(VU_WINDOW) }
    val meters = remember { listOf(VuMeter(), VuMeter()) }
    val frame by rememberAudioFrames(tap) { dt ->
        val rate = tap.read(left, right)
        meters[0].step(left.takeIf { rate > 0 }, dt)
        meters[1].step(right.takeIf { rate > 0 }, dt)
        rate > 0 || meters.any { !it.settled }
    }
    val c = Dab.colors
    val text = rememberTextMeasurer(cacheSize = 32)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for ((i, meter) in meters.withIndex()) {
            Canvas(Modifier.fillMaxWidth().weight(1f)) {
                if (frame == Long.MIN_VALUE) return@Canvas
                drawVuMeter(meter, if (i == 0) "L" else "R", text, c)
            }
        }
    }
}

private fun DrawScope.drawVuMeter(meter: VuMeter, channel: String, text: TextMeasurer, c: DabColors) {
    val w = size.width
    val h = size.height
    val corner = CornerRadius(18.dp.toPx())
    drawRoundRect(c.placeholder, cornerRadius = corner)
    drawRoundRect(
        Brush.radialGradient(listOf(c.accent.copy(alpha = 0.26f), c.accent.copy(alpha = 0.05f)), center = Offset(w / 2, h), radius = max(w, h) * 0.8f),
        cornerRadius = corner,
    )

    // The needle pivots just below the face, as on a real meter.
    val pivot = Offset(w / 2, h * 1.12f)
    val radius = h * 0.88f
    fun angle(p: Float) = (-SWEEP_DEG / 2 + SWEEP_DEG * p) * PI.toFloat() / 180
    fun at(r: Float, p: Float) = Offset(pivot.x + r * sin(angle(p)), pivot.y - r * cos(angle(p)))

    val zero = VuMeter.position(0f)
    val arcTopLeft = Offset(pivot.x - radius, pivot.y - radius)
    val arcSize = Size(radius * 2, radius * 2)
    drawArc(c.text.copy(alpha = 0.7f), -90 - SWEEP_DEG / 2, SWEEP_DEG * zero, false, arcTopLeft, arcSize, style = Stroke(2.dp.toPx()))
    drawArc(VU_RED, -90 - SWEEP_DEG / 2 + SWEEP_DEG * zero, SWEEP_DEG * (1 - zero), false, arcTopLeft, arcSize, style = Stroke(6.dp.toPx()))
    val labelStyle = dabText(14, FontWeight.Bold)
    for (vu in VU_MARKS) {
        val p = VuMeter.position(vu.toFloat())
        val colour = if (vu > 0) VU_RED else c.text.copy(alpha = 0.7f)
        drawLine(colour, at(radius, p), at(radius * 1.07f, p), 2.dp.toPx())
        if (vu in VU_LABELS) {
            val label = text.measure(if (vu > 0) "+$vu" else "$vu", labelStyle.copy(color = colour))
            val centre = at(radius * 1.16f, p)
            drawText(label, topLeft = Offset(centre.x - label.size.width / 2, centre.y - label.size.height / 2))
        }
    }
    val vu = text.measure("VU", dabText(22, FontWeight.ExtraBold).copy(color = c.text.copy(alpha = 0.75f)))
    drawText(vu, topLeft = Offset(w / 2 - vu.size.width / 2, h * 0.6f))
    val name = text.measure(channel, dabText(18, FontWeight.ExtraBold).copy(color = c.muted))
    drawText(name, topLeft = Offset(14.dp.toPx(), h - name.size.height - 10.dp.toPx()))

    val light = Offset(w - 24.dp.toPx(), h - 22.dp.toPx())
    val peakLabel = text.measure("PEAK", dabText(12, FontWeight.Bold).copy(color = c.muted))
    drawText(peakLabel, topLeft = Offset(light.x - 14.dp.toPx() - peakLabel.size.width, light.y - peakLabel.size.height / 2))
    if (meter.peak) {
        drawCircle(VU_RED.copy(alpha = 0.35f), 13.dp.toPx(), light)
        drawCircle(VU_RED, 7.dp.toPx(), light)
    } else {
        drawCircle(c.text.copy(alpha = 0.15f), 7.dp.toPx(), light)
    }

    clipRect {
        drawLine(c.text, pivot, at(radius * 1.04f, meter.needle), 3.dp.toPx(), cap = StrokeCap.Round)
    }
}

/** Bars radiating round the station logo, bass at the top, with the logo pulsing on the beat. */
@Composable
private fun RadialSpectrum(tap: SpectrumTap, service: DabServiceInfo, logo: ImageBitmap?, modifier: Modifier) {
    val c = Dab.colors
    val analyser = remember { SpectrumAnalyser() }
    val smoother = remember { BandSmoother(analyser.bands) }
    val samples = remember { FloatArray(analyser.fftSize) }
    val target = remember { FloatArray(analyser.bands) }
    val frame by rememberAudioFrames(tap) { dt ->
        val rate = tap.read(samples)
        if (rate > 0) analyser.analyse(samples, rate, target) else target.fill(0f)
        smoother.step(target, dt)
        rate > 0 || !smoother.settled
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val logoSize = min(maxWidth.value, maxHeight.value).dp * RADIAL_LOGO
        Canvas(Modifier.fillMaxSize()) {
            if (frame == Long.MIN_VALUE) return@Canvas
            val levels = smoother.levels
            val spokes = levels.size * 2
            val inner = logoSize.toPx() / 2 * (1 + RADIAL_PULSE) + 10.dp.toPx()
            val outer = min(size.width, size.height) / 2 - 14.dp.toPx()
            val width = (2 * PI.toFloat() * inner / spokes * 0.55f).coerceAtLeast(2.dp.toPx())
            for (s in 0 until spokes) {
                // Mirrored left and right, so the bass meets at the top and the treble at the bottom.
                val level = levels[if (s < levels.size) s else spokes - 1 - s]
                val a = 2 * PI.toFloat() * (s + 0.5f) / spokes
                val length = max(width, (outer - inner) * level)
                val dir = Offset(sin(a), -cos(a))
                drawLine(
                    c.accent.copy(alpha = 0.45f + 0.55f * level),
                    center + dir * inner,
                    center + dir * (inner + length),
                    width,
                    cap = StrokeCap.Round,
                )
            }
        }
        Box(
            Modifier
                .size(logoSize)
                .graphicsLayer {
                    // Reading the frame here moves the logo without recomposing.
                    if (frame != Long.MIN_VALUE) {
                        val levels = smoother.levels
                        val bass = (levels[0] + levels[1] + levels[2] + levels[3]) / 4
                        val scale = 1 + RADIAL_PULSE * ((bass - RADIAL_BASS_FROM) / (1 - RADIAL_BASS_FROM)).coerceIn(0f, 1f)
                        scaleX = scale
                        scaleY = scale
                    }
                }
                .clip(CircleShape)
                .background(c.card),
            contentAlignment = Alignment.Center,
        ) {
            if (logo != null) {
                Image(logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(logoSize * 0.12f))
            } else {
                MonogramTile(service.serviceId, service.label, size = logoSize, corner = logoSize / 2, fontSize = (logoSize.value / 3).toInt())
            }
        }
    }
}

/** A scrolling picture of the spectrum over the last few seconds: low notes left, newest at the top. */
@Composable
private fun Waterfall(tap: SpectrumTap, modifier: Modifier) {
    val c = Dab.colors
    val analyser = remember { SpectrumAnalyser(bands = WATERFALL_BANDS) }
    val samples = remember { FloatArray(analyser.fftSize) }
    val target = remember { FloatArray(analyser.bands) }
    val pixels = remember { IntArray(WATERFALL_BANDS * WATERFALL_ROWS) }
    val bitmap = remember { Bitmap.createBitmap(WATERFALL_BANDS, WATERFALL_ROWS, Bitmap.Config.ARGB_8888) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val palette = remember(c.accent, c.text) { waterfallPalette(c.accent, c.text) }
    val state = remember { WaterfallState() }
    val frame by rememberAudioFrames(tap) { dt ->
        val rate = tap.read(samples)
        if (rate > 0) analyser.analyse(samples, rate, target) else target.fill(0f)
        state.untilRow -= dt
        if (state.untilRow <= 0f) {
            state.untilRow += WATERFALL_ROW_S
            if (state.untilRow < 0f) state.untilRow = 0f
            pixels.copyInto(pixels, WATERFALL_BANDS, 0, pixels.size - WATERFALL_BANDS)
            for (b in 0 until WATERFALL_BANDS) {
                val v = ((target[b] - WATERFALL_FLOOR) / (1 - WATERFALL_FLOOR)).coerceIn(0f, 1f)
                pixels[b] = palette[(v * (palette.size - 1)).toInt()]
            }
            bitmap.setPixels(pixels, 0, WATERFALL_BANDS, 0, 0, WATERFALL_BANDS, WATERFALL_ROWS)
            state.quietRows = if (rate > 0) 0 else state.quietRows + 1
        }
        rate > 0 || state.quietRows < WATERFALL_ROWS
    }

    Canvas(modifier) {
        if (frame == Long.MIN_VALUE) return@Canvas
        drawImage(
            image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(WATERFALL_BANDS, WATERFALL_ROWS),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.Low,
        )
    }
}

private class WaterfallState {
    var untilRow = 0f
    var quietRows = WATERFALL_ROWS
}

/** Colours from transparent, for silence, through the accent to near white for the loudest. */
private fun waterfallPalette(accent: Color, text: Color): IntArray = IntArray(256) { i ->
    val v = i / 255f
    when {
        v < 0.5f -> accent.copy(alpha = v / 0.5f * 0.45f)
        v < 0.8f -> lerp(accent.copy(alpha = 0.45f), accent, (v - 0.5f) / 0.3f)
        else -> lerp(accent, lerp(accent, text, 0.75f), (v - 0.8f) / 0.2f)
    }.toArgb()
}

private val VU_RED = Color(0xFFE5484D)
private val VU_MARKS = listOf(-20, -10, -7, -5, -3, -2, -1, 0, 1, 2, 3)
private val VU_LABELS = setOf(-20, -10, -7, -5, -3, 0, 3)
private const val SWEEP_DEG = 100f

private const val SCOPE_SPAN = 1024
private const val SCOPE_STRIDE = 2
private const val SCOPE_FILL = 0.85f
private const val SCOPE_MIN_PEAK = 0.02f

private const val VU_WINDOW = 2048

private const val RADIAL_LOGO = 0.42f
private const val RADIAL_PULSE = 0.1f
private const val RADIAL_BASS_FROM = 0.55f

private const val WATERFALL_BANDS = 48
private const val WATERFALL_ROWS = 120
private const val WATERFALL_ROW_S = 1f / 30
private const val WATERFALL_FLOOR = 0.25f

private const val IDLE_POLL_MS = 200L
