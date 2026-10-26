package com.iamburny.waveband.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iamburny.waveband.fm.FmBand
import com.iamburny.waveband.fm.FmState
import com.iamburny.waveband.fm.HeadUnitRadio
import com.iamburny.waveband.ui.theme.Dab
import com.iamburny.waveband.ui.theme.OnAccent
import com.iamburny.waveband.ui.theme.dabText
import kotlinx.coroutines.delay

/** The source the Now Playing screen shows; FM and AM are bands of the head unit's tuner. */
enum class ListenSource(val label: String) { DAB("DAB"), FM("FM"), AM("AM") }

/** DAB · FM · AM switch, shown when the head unit has its own tuner. */
@Composable
fun SourceSwitch(selected: ListenSource, dabLabel: String, onSelect: (ListenSource) -> Unit) {
    val c = Dab.colors
    val shape = RoundedCornerShape(24.dp)
    Row(Modifier.height(48.dp).background(c.btn, shape).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (source in ListenSource.entries) {
            val on = source == selected
            val pill = RoundedCornerShape(20.dp)
            Box(
                Modifier
                    .fillMaxHeight()
                    .background(if (on) c.accent else Color.Transparent, pill)
                    .pressable(pill, onClickLabel = "Listen to ${source.label}") { onSelect(source) }
                    .semantics { this.selected = on }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (source == ListenSource.DAB) dabLabel else source.label,
                    style = dabText(20, FontWeight.ExtraBold, letterSpacing = 0.5.sp),
                    color = if (on) OnAccent else c.text,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Header of the FM/AM view: the source switch, what the tuner reports about the signal, and the
 * Local/DX sensitivity toggle.
 */
@Composable
fun FmStatusRow(state: FmState, fm: HeadUnitRadio, onSelectSource: (ListenSource) -> Unit) {
    val c = Dab.colors
    val band = state.band ?: FmBand.FM
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SourceSwitch(if (band == FmBand.FM) ListenSource.FM else ListenSource.AM, "DAB", onSelectSource)
        val flags = listOfNotNull(
            "Seeking…".takeIf { state.scanning },
            (if (state.stereo) "Stereo" else "Mono").takeIf { band == FmBand.FM },
            "RDS".takeIf { state.rds && band == FmBand.FM },
            "TP".takeIf { state.trafficProgramme },
            "TA".takeIf { state.trafficAnnouncements },
        ).joinToString(" · ")
        Text(flags, style = dabText(22, FontWeight.SemiBold), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        LocalToggle(state.local) { fm.toggleLocal() }
        Text("Head unit tuner", style = dabText(20), color = c.muted, maxLines = 1)
    }
}

/**
 * Shows the tuner's sensitivity; Local only stops on strong stations when seeking. The tuner key
 * flips the setting, and the tuner takes a moment to report the new one, so the expected setting
 * is shown meanwhile and further taps wait for it: each would flip it again.
 */
@Composable
private fun LocalToggle(reported: Boolean, onToggle: () -> Unit) {
    val c = Dab.colors
    val shape = RoundedCornerShape(24.dp)
    var pending by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(pending, reported) {
        if (pending == reported) pending = null
        if (pending != null) {
            delay(LOCAL_CONFIRM_MS)
            pending = null
        }
    }
    val local = pending ?: reported
    Box(
        Modifier
            .height(48.dp)
            .background(if (local) c.accent else c.btn, shape)
            .pressable(shape, onClickLabel = if (local) "Switch to DX, to find weaker stations" else "Switch to Local, to find only strong stations") {
                if (pending == null) {
                    pending = !reported
                    onToggle()
                }
            }
            .semantics { contentDescription = if (local) "Sensitivity: Local" else "Sensitivity: DX" }
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (local) "Local" else "DX", style = dabText(20, FontWeight.ExtraBold, letterSpacing = 0.5.sp), color = if (local) OnAccent else c.text, maxLines = 1)
    }
}

private const val LOCAL_CONFIRM_MS = 3_000L

/** Frequency tile and tuner controls for the head unit's FM/AM tuner; [presetSlot] is the Waveband preset playing, if any. */
@Composable
fun FmPlaying(state: FmState, savedName: String?, presetSlot: Int?, fm: HeadUnitRadio, actions: RadioActions, modifier: Modifier) {
    val c = Dab.colors
    val band = state.band ?: FmBand.FM
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        FrequencyTile(state, band)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val kicker = listOfNotNull(presetSlot?.let { "Preset ${it + 1}" }, band.name).joinToString(" · ")
            Text(
                kicker.uppercase(),
                style = dabText(22, FontWeight.Bold, letterSpacing = 1.sp),
                color = c.accentText,
                maxLines = 1,
            )
            // The live RDS name, or the one heard here before while RDS hasn't come through yet.
            val name = state.stationName.ifBlank { null } ?: savedName
            Text(
                name ?: state.frequencyLabel.orEmpty(),
                style = dabText(64, FontWeight.ExtraBold, letterSpacing = (-1.5).sp, lineHeight = 67.sp),
                color = c.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            val status = when {
                state.ours -> name?.let { state.frequencyLabel }
                state.active -> "Playing in the head unit's radio app"
                else -> "Off · press play to listen"
            }
            if (status != null) {
                Text(status, style = dabText(30, FontWeight.SemiBold, lineHeight = 39.sp), color = c.sub, maxLines = 2, modifier = Modifier.padding(top = 20.dp))
            }
            Spacer(Modifier.weight(1f).heightIn(min = 16.dp))
            val step = if (band == FmBand.FM) "50 kHz" else "9 kHz"
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val square = RoundedCornerShape(28.dp)
                DabIconButton(DabIcons.Previous, "Tune down $step", 112.dp, 48.dp, c.btn, c.text, shape = square) { fm.step(-1) }
                DabIconButton(DabIcons.SeekDown, "Seek down", 112.dp, 48.dp, c.btn, c.text, shape = square) { fm.seek(-1) }
                DabIconButton(
                    if (state.ours) DabIcons.Pause else DabIcons.Play,
                    if (state.ours) "Turn off $band" else "Listen to $band",
                    144.dp, 60.dp, c.accent, OnAccent,
                ) { actions.togglePlayPause() }
                DabIconButton(DabIcons.SeekUp, "Seek up", 112.dp, 48.dp, c.btn, c.text, shape = square) { fm.seek(1) }
                DabIconButton(DabIcons.Next, "Tune up $step", 112.dp, 48.dp, c.btn, c.text, shape = square) { fm.step(1) }
            }
        }
    }
}

@Composable
private fun FrequencyTile(state: FmState, band: FmBand) {
    val c = Dab.colors
    val shape = RoundedCornerShape(28.dp)
    val number = when (band) {
        FmBand.FM -> "%d.%02d".format(state.frequency / 100, state.frequency % 100)
        FmBand.AM -> "${state.frequency}"
    }
    Column(
        Modifier
            .fillMaxHeight()
            .aspectRatio(1f)
            .clip(shape)
            .background(c.nowCard)
            .dabBorder(3.dp, c.idleBorder, shape)
            .semantics(mergeDescendants = true) { contentDescription = state.frequencyLabel.orEmpty() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(band.name, style = dabText(36, FontWeight.ExtraBold, letterSpacing = 2.sp), color = c.accentText)
        Text(number, style = dabText(if (number.length > 5) 76 else 88, FontWeight.ExtraBold, letterSpacing = (-2).sp), color = c.text, maxLines = 1)
        Text(if (band == FmBand.FM) "MHz" else "kHz", style = dabText(28, FontWeight.Bold), color = c.muted)
    }
}
