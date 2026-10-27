package com.iamburny.waveband.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iamburny.waveband.RadioController
import com.iamburny.waveband.SignalSample
import com.iamburny.waveband.backend.DabServiceInfo
import com.iamburny.waveband.backend.SignalInfo
import com.iamburny.waveband.ui.theme.Dab
import com.iamburny.waveband.ui.theme.dabText
import kotlinx.coroutines.delay

/** Live DAB reception of the tuned ensemble: quality, error rates and a graph of the last two minutes. */
@Composable
fun SignalScreen(radio: RadioController, service: DabServiceInfo, onClose: () -> Unit) {
    val c = Dab.colors
    val state by radio.state.collectAsStateWithLifecycle()
    val history by radio.signalHistory.collectAsStateWithLifecycle()
    val signal = state.signal
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DabIconButton(DabIcons.Back, "Back", size = 64.dp, iconSize = 32.dp, background = c.btn, tint = c.text, onClick = onClose)
            ScreenTitle("Signal")
            val ensemble = listOf(service.ensembleLabel.ifBlank { null }, "Block ${service.channelLabel}").filterNotNull().joinToString(" · ")
            Text(ensemble, style = dabText(22, FontWeight.SemiBold), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            val quality = signal?.quality
            Readout(
                value = quality?.toString() ?: "–",
                label = qualityLabel(signal),
                caption = "Quality",
                modifier = Modifier.weight(1.3f),
                accent = true,
            )
            Readout(percent(signal?.errorRate), "Of the signal arriving damaged", "Error rate", Modifier.weight(1f))
            Readout(percent(signal?.audioFrameLoss), "Of the audio lost", "Dropouts", Modifier.weight(1f))
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(c.card, RoundedCornerShape(22.dp))
                .dabBorder(2.dp, c.cardBorder, RoundedCornerShape(22.dp))
                .padding(horizontal = 28.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Quality, last 2 minutes", style = dabText(20, FontWeight.Bold), color = c.text, modifier = Modifier.weight(1f))
                Text("Dropouts", style = dabText(18, FontWeight.SemiBold), color = c.muted)
                Box(Modifier.padding(start = 8.dp).size(8.dp, 14.dp).background(c.muted.copy(alpha = 0.45f), RoundedCornerShape(3.dp)))
            }
            QualityGraph(history, Modifier.weight(1f).fillMaxWidth())
            Row(Modifier.fillMaxWidth()) {
                Text("2 min ago", style = dabText(16), color = c.muted, modifier = Modifier.weight(1f))
                Text("Now", style = dabText(16), color = c.muted)
            }
        }
    }
}

@Composable
private fun Readout(value: String, label: String, caption: String, modifier: Modifier, accent: Boolean = false) {
    val c = Dab.colors
    Column(
        modifier
            .fillMaxSize()
            .background(c.card, RoundedCornerShape(22.dp))
            .dabBorder(2.dp, c.cardBorder, RoundedCornerShape(22.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(caption.uppercase(), style = dabText(18, FontWeight.Bold, letterSpacing = 1.sp), color = if (accent) c.accentText else c.muted, maxLines = 1)
        Text(value, style = dabText(52, FontWeight.ExtraBold, letterSpacing = (-1).sp), color = c.text, maxLines = 1)
        Text(label, style = dabText(18, FontWeight.SemiBold), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Quality as a line, with a bar wherever audio dropped out; scrolls with the clock. */
@Composable
private fun QualityGraph(history: List<SignalSample>, modifier: Modifier) {
    val c = Dab.colors
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(1_000)
            value = SystemClock.elapsedRealtime()
        }
    }
    val latest = history.lastOrNull()?.signal?.quality
    Canvas(modifier.semantics { contentDescription = latest?.let { "Signal quality graph, now $it out of 100" } ?: "Signal quality graph, no readings yet" }) {
        val span = RadioController.SIGNAL_HISTORY_MS.toFloat()
        fun x(at: Long) = size.width * (1f - (now - at) / span)
        fun y(quality: Int) = size.height * (1f - quality / 100f)

        for (q in listOf(25, 50, 75)) {
            drawLine(c.outline.copy(alpha = 0.5f), Offset(0f, y(q)), Offset(size.width, y(q)), 1.dp.toPx())
        }
        val barWidth = 4.dp.toPx()
        for (sample in history) {
            val loss = sample.signal.audioFrameLoss ?: continue
            if (loss <= 0.005f) continue
            val h = size.height * loss.coerceIn(0.05f, 1f)
            drawRect(c.muted.copy(alpha = 0.45f), Offset(x(sample.atMillis) - barWidth / 2, size.height - h), Size(barWidth, h))
        }
        val points = history.mapNotNull { s -> s.signal.quality?.let { Offset(x(s.atMillis), y(it)) } }.filter { it.x >= 0f }
        if (points.size >= 2) {
            val line = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            val fill = Path().apply {
                addPath(line)
                lineTo(points.last().x, size.height)
                lineTo(points.first().x, size.height)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.35f), c.accent.copy(alpha = 0f))))
            drawPath(line, c.accent, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        points.lastOrNull()?.let { drawCircle(c.accent, 7.dp.toPx(), it) }
    }
}

private fun percent(value: Float?): String = when {
    value == null -> "–"
    value == 0f -> "0%"
    value < 0.001f -> "<0.1%"
    value < 0.1f -> "%.1f%%".format(value * 100)
    else -> "%.0f%%".format(value * 100)
}

private fun qualityLabel(signal: SignalInfo?): String {
    val quality = signal?.quality ?: return "No reading yet"
    return when {
        !signal.locked || quality == 0 -> "No usable signal"
        quality >= 80 -> "Excellent"
        quality >= 60 -> "Good"
        quality >= 35 -> "Fair"
        else -> "Poor, expect dropouts"
    }
}
