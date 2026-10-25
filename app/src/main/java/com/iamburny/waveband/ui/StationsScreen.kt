package com.iamburny.waveband.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iamburny.waveband.RadioController
import com.iamburny.waveband.StationOrder
import com.iamburny.waveband.backend.ProgrammeType
import com.iamburny.waveband.data.Station
import com.iamburny.waveband.ui.theme.Dab
import com.iamburny.waveband.ui.theme.OnAccent
import com.iamburny.waveband.ui.theme.dabText

private enum class StationFilter(val label: String) { ALL("All"), FAVOURITES("Favourites"), MUSIC("Music"), TALK("Talk") }

@Composable
fun StationsScreen(radio: RadioController, actions: RadioActions) {
    val c = Dab.colors
    val stations by radio.stations.collectAsStateWithLifecycle()
    val state by radio.state.collectAsStateWithLifecycle()
    val alphabetical by radio.alphabetical.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(StationFilter.ALL) }

    val shown = remember(stations, filter, alphabetical) {
        val byKey = stations.associateBy { it.key }
        val infos = stations.map { it.info }
        val ordered = if (alphabetical) StationOrder.alphabetical(infos) else StationOrder.byEnsemble(infos)
        ordered.mapNotNull { byKey[it.key] }.filter { s ->
            when (filter) {
                StationFilter.ALL -> true
                StationFilter.FAVOURITES -> s.favourite
                StationFilter.MUSIC -> ProgrammeType.kind(s.info.programmeType) == ProgrammeType.Kind.MUSIC
                StationFilter.TALK -> ProgrammeType.kind(s.info.programmeType) == ProgrammeType.Kind.TALK
            }
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenTitle("Stations")
            Text("${stations.size} found", style = dabText(20), color = c.muted, maxLines = 1, modifier = Modifier.padding(start = 12.dp, end = 8.dp))
            Spacer(Modifier.weight(1f))
            for (f in StationFilter.entries) {
                FilterChip(f.label, selected = filter == f) { filter = f }
            }
            FilterChip("A–Z", selected = alphabetical) { radio.setAlphabetical(!alphabetical) }
        }
        if (shown.isEmpty()) {
            EmptyStations(
                message = when {
                    stations.isEmpty() && state.scan != null -> "Scanning… stations appear here as they are found."
                    stations.isEmpty() -> "No stations yet. Connect the tuner and run a full scan in Settings."
                    filter == StationFilter.FAVOURITES -> "No favourites yet. Tap the heart on a station to add it."
                    else -> "No ${filter.label.lowercase()} stations found."
                },
            )
        } else {
            // Opens scrolled to the playing station, keeping one row above it in view.
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = (shown.indexOfFirst { it.key == state.current?.key } - 1).coerceAtLeast(0),
            )
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(shown, key = { it.key }) { station ->
                    val nowPlaying = station.key == state.current?.key
                    val label = state.tunedMetadata?.dynamicLabel?.takeIf { nowPlaying && it.isNotBlank() }
                    StationRow(station, nowPlaying, label, onPlay = { actions.play(station.info) }) {
                        radio.setFavourite(station.key, !station.favourite)
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Dab.colors
    Box(
        Modifier
            .height(64.dp)
            .background(if (selected) c.invBg else c.chip, CircleShape)
            .pressable(CircleShape, onClick = onClick)
            .semantics {
                role = Role.Checkbox
                stateDescription = if (selected) "On" else "Off"
                this.selected = selected
            }
            .padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = dabText(22, FontWeight.Bold), color = if (selected) c.invFg else c.text, maxLines = 1)
    }
}

@Composable
private fun StationRow(station: Station, nowPlaying: Boolean, dynamicLabel: String?, onPlay: () -> Unit, onToggleFavourite: () -> Unit) {
    val c = Dab.colors
    val info = station.info
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(104.dp)
            .background(if (nowPlaying) c.selectedRowBg else c.card, shape)
            .dabBorder(3.dp, if (nowPlaying) c.accent else c.idleBorder, shape)
            .pressable(shape, onClickLabel = "Play", onClick = onPlay)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        MonogramTile(info.serviceId, info.label, size = 72.dp, corner = 16.dp, fontSize = 26)
        Column(Modifier.weight(1f)) {
            Text(info.label, style = dabText(30, FontWeight.ExtraBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = dynamicLabel ?: ProgrammeType.name(info.programmeType) ?: "Block ${info.channelLabel}"
            Text(
                listOf(info.ensembleLabel, detail).filter { it.isNotBlank() }.joinToString(" · "),
                style = dabText(20),
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (nowPlaying) {
            Text(
                "Playing",
                style = dabText(18, FontWeight.ExtraBold),
                color = OnAccent,
                modifier = Modifier.background(c.accent, CircleShape).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        DabIconButton(
            icon = if (station.favourite) DabIcons.HeartFilled else DabIcons.HeartOutline,
            contentDescription = if (station.favourite) "Remove ${info.label} from favourites" else "Add ${info.label} to favourites",
            size = 80.dp,
            iconSize = 36.dp,
            background = c.btn,
            tint = if (station.favourite) c.accentText else c.muted,
            onClick = onToggleFavourite,
        )
    }
}

@Composable
private fun EmptyStations(message: String) {
    val c = Dab.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(160.dp)
            .background(c.card, RoundedCornerShape(22.dp))
            .dabBorder(3.dp, c.idleBorder, RoundedCornerShape(22.dp))
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(message, style = dabText(24, FontWeight.SemiBold), color = c.sub)
    }
}
