package com.iamburny.waveband.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iamburny.waveband.DabTunerApp
import com.iamburny.waveband.RadioController
import com.iamburny.waveband.data.PRESET_COUNT
import com.iamburny.waveband.data.Source
import com.iamburny.waveband.ui.theme.Dab
import com.iamburny.waveband.ui.theme.dabText

@Composable
fun PresetsScreen(radio: RadioController, actions: RadioActions) {
    val c = Dab.colors
    val stations by radio.stations.collectAsStateWithLifecycle()
    val presets by radio.presets.collectAsStateWithLifecycle()
    val state by radio.state.collectAsStateWithLifecycle()
    val source by radio.source.collectAsStateWithLifecycle()
    val app = DabTunerApp.from(LocalContext.current)
    val fmState by app.headUnitRadio.state.collectAsStateWithLifecycle()
    val fmNames by app.fmStationNames.names.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    val drag = rememberPresetDragState(PRESET_COUNT) { a, b -> radio.swapPresets(a, b) }
    // What a long-press saves: the FM/AM frequency while listening to the tuner, else the DAB station.
    val current = if (source == Source.FM && fmState.connected && fmState.active) {
        PresetItem.of(fmState.key, emptyList(), fmNames, tunerAvailable = true)
    } else {
        state.current?.let { cur -> PresetItem.of(cur.key, stations, fmNames, false) }
    }

    BackHandler(enabled = editing) { editing = false }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ScreenTitle("Presets")
            Text(
                when {
                    editing && current != null -> "Tap a tile to save ${current.title} there"
                    editing -> "Tap × to clear a preset"
                    else -> "Long-press a tile to save what's playing, drag to move it"
                },
                style = dabText(20),
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
            PillButton(if (editing) "Done" else "Edit", c.presetsBtn, c.text) { editing = !editing }
        }
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            for (row in 0 until 3) {
                // The row holding a dragged tile goes on top, so the tile passes over the other rows.
                Row(Modifier.weight(1f).fillMaxWidth().zIndex(if (drag.dragging?.div(4) == row) 1f else 0f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (col in 0 until 4) {
                        val slot = row * 4 + col
                        if (slot >= PRESET_COUNT) continue
                        PresetTile(
                            slot = slot,
                            item = PresetItem.of(presets.getOrNull(slot), stations, fmNames, fmState.connected),
                            current = current,
                            editing = editing,
                            drag = drag,
                            radio = radio,
                            actions = actions,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetTile(
    slot: Int,
    item: PresetItem?,
    current: PresetItem?,
    editing: Boolean,
    drag: PresetDragState,
    radio: RadioController,
    actions: RadioActions,
    modifier: Modifier,
) {
    val c = Dab.colors
    val shape = RoundedCornerShape(24.dp)
    val selected = item != null && item.key == current?.key
    val base = modifier.presetDrag(drag, slot, enabled = item != null) { "preset ${it + 1}" }
    val decorated = when {
        drag.target == slot -> base.background(c.selectedBg, shape).dabBorder(3.dp, c.accent, shape)
        item == null -> base.dashedBorder(3.dp, c.emptyBorder, 24.dp)
        selected -> base.background(c.selectedBg, shape).dabBorder(3.dp, c.accent, shape)
        else -> base.background(c.card, shape).dabBorder(3.dp, c.idleBorder, shape)
    }
    val saveCurrent: (() -> Unit)? = current?.let { cur -> { radio.setPreset(slot, cur.key) } }
    val onClick: () -> Unit = when {
        editing || item == null -> saveCurrent ?: {}
        item is PresetItem.DabStation -> ({ actions.play(item.station.info) })
        item is PresetItem.Tuner -> ({ if (item.available) actions.playFm(item.band, item.frequency) })
        else -> ({})
    }
    val description = when (item) {
        null -> "Empty preset ${slot + 1}, long-press to save what's playing"
        is PresetItem.Tuner -> "Preset ${slot + 1}, ${listOfNotNull(item.name, item.frequencyLabel).joinToString(", ")}"
        else -> "Preset ${slot + 1}, ${item.title}"
    }
    Column(
        decorated
            .pressable(
                shape,
                onClickLabel = if (editing || item == null) "Save what's playing" else "Play",
                onLongClickLabel = "Save what's playing",
                onLongClick = saveCurrent,
            ) { onClick() }
            .semantics { contentDescription = description }
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${slot + 1}", style = dabText(30, FontWeight.ExtraBold), color = if (selected) c.accentText else c.muted)
            if (item != null) {
                if (editing) {
                    DabIconButton(
                        DabIcons.Close,
                        "Clear preset ${slot + 1}",
                        size = 56.dp,
                        iconSize = 28.dp,
                        background = c.btn,
                        tint = c.text,
                        shape = RoundedCornerShape(14.dp),
                    ) { radio.clearPreset(slot) }
                } else {
                    when (item) {
                        is PresetItem.DabStation -> StationTile(item.station.info, size = 56.dp, corner = 14.dp, fontSize = 20)
                        is PresetItem.Tuner -> BandTile(item.band, size = 56.dp, corner = 14.dp, fontSize = 20)
                    }
                }
            } else {
                Box(Modifier.height(56.dp))
            }
        }
        Column {
            Text(
                item?.title ?: "+ Save here",
                style = dabText(26, FontWeight.ExtraBold, lineHeight = 30.sp),
                color = if (item != null && (item !is PresetItem.Tuner || item.available)) c.text else c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item is PresetItem.Tuner && item.name != null) {
                Text(item.frequencyLabel, style = dabText(20, FontWeight.SemiBold), color = c.muted, maxLines = 1)
            }
        }
    }
}
