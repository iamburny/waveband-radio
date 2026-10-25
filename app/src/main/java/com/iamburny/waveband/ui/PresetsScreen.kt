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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iamburny.waveband.RadioController
import com.iamburny.waveband.backend.DabServiceInfo
import com.iamburny.waveband.data.PRESET_COUNT
import com.iamburny.waveband.data.Station
import com.iamburny.waveband.ui.theme.Dab
import com.iamburny.waveband.ui.theme.dabText

@Composable
fun PresetsScreen(radio: RadioController, actions: RadioActions) {
    val c = Dab.colors
    val stations by radio.stations.collectAsStateWithLifecycle()
    val presets by radio.presets.collectAsStateWithLifecycle()
    val state by radio.state.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    val current = state.current

    BackHandler(enabled = editing) { editing = false }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ScreenTitle("Presets")
            Text(
                when {
                    editing && current != null -> "Tap a tile to save ${current.label} there"
                    editing -> "Tap × to clear a preset"
                    else -> "Long-press a tile to save the current station"
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
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (col in 0 until 4) {
                        val slot = row * 4 + col
                        if (slot >= PRESET_COUNT) continue
                        val key = presets.getOrNull(slot)
                        PresetTile(
                            slot = slot,
                            station = key?.let { k -> stations.firstOrNull { it.key == k } },
                            current = current,
                            editing = editing,
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
    station: Station?,
    current: DabServiceInfo?,
    editing: Boolean,
    radio: RadioController,
    actions: RadioActions,
    modifier: Modifier,
) {
    val c = Dab.colors
    val shape = RoundedCornerShape(24.dp)
    val selected = station != null && station.key == current?.key
    val decorated = when {
        station == null -> modifier.dashedBorder(3.dp, c.emptyBorder, 24.dp)
        selected -> modifier.background(c.selectedBg, shape).dabBorder(3.dp, c.accent, shape)
        else -> modifier.background(c.card, shape).dabBorder(3.dp, c.idleBorder, shape)
    }
    val saveCurrent: (() -> Unit)? = current?.let { cur -> { radio.setPreset(slot, cur.key) } }
    val onClick: () -> Unit = when {
        editing || station == null -> saveCurrent ?: {}
        else -> ({ actions.play(station.info) })
    }
    val description = when {
        station == null -> "Empty preset ${slot + 1}, long-press to save current station"
        else -> "Preset ${slot + 1}, ${station.info.label}"
    }
    Column(
        decorated
            .pressable(
                shape,
                onClickLabel = if (editing || station == null) "Save current station" else "Play",
                onLongClickLabel = "Save current station",
                onLongClick = saveCurrent,
            ) { onClick() }
            .semantics { contentDescription = description }
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${slot + 1}", style = dabText(30, FontWeight.ExtraBold), color = if (selected) c.accentText else c.muted)
            if (station != null) {
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
                    StationTile(station.info, size = 56.dp, corner = 14.dp, fontSize = 20)
                }
            } else {
                Box(Modifier.height(56.dp))
            }
        }
        Text(
            station?.info?.label ?: "+ Save here",
            style = dabText(26, FontWeight.ExtraBold, lineHeight = 30.sp),
            color = if (station != null) c.text else c.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
