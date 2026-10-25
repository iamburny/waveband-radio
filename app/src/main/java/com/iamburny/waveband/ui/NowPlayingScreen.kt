package com.iamburny.waveband.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iamburny.waveband.RadioController
import com.iamburny.waveband.RadioState
import com.iamburny.waveband.backend.DabServiceInfo
import com.iamburny.waveband.backend.ProgrammeType
import com.iamburny.waveband.backend.TunerState
import com.iamburny.waveband.data.AppSettings
import com.iamburny.waveband.data.Station
import com.iamburny.waveband.ui.theme.Dab
import com.iamburny.waveband.ui.theme.OnAccent
import com.iamburny.waveband.ui.theme.dabText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun NowPlayingScreen(
    radio: RadioController,
    settings: AppSettings,
    playWhenReady: Boolean,
    demoAvailable: Boolean,
    actions: RadioActions,
    onOpenStations: () -> Unit,
) {
    val state by radio.state.collectAsStateWithLifecycle()
    val stations by radio.stations.collectAsStateWithLifecycle()
    val presets by radio.presets.collectAsStateWithLifecycle()
    val current = state.current
    val station = current?.let { cur -> stations.firstOrNull { it.key == cur.key } }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (state.tunerOpen && current != null && state.scan == null) {
            StatusRow(state, state.tuned ?: current)
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Artwork(state, current, settings.slideshow)
                StationInfo(
                    state = state,
                    service = current,
                    favourite = station?.favourite == true,
                    presetSlot = presets.indexOf(current.key).takeIf { it >= 0 },
                    playWhenReady = playWhenReady,
                    radio = radio,
                    actions = actions,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        } else {
            TunerCard(state, stations.isNotEmpty(), demoAvailable, radio, actions, onOpenStations, Modifier.weight(1f).fillMaxWidth())
        }
        PresetStrip(presets.take(6), stations, current, radio, actions)
    }
}

@Composable
private fun StatusRow(state: RadioState, service: DabServiceInfo) {
    val c = Dab.colors
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            if (service.isDabPlus) "DAB+" else "DAB",
            style = dabText(20, FontWeight.ExtraBold, letterSpacing = 0.5.sp),
            color = c.text,
            modifier = Modifier.background(c.btn, CircleShape).padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val ensemble = listOf(service.ensembleLabel.ifBlank { null }, "Block ${service.channelLabel}").filterNotNull().joinToString(" · ")
        Text(ensemble, style = dabText(22, FontWeight.SemiBold), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        val audio = listOfNotNull(
            service.bitrateKbps.takeIf { it > 0 }?.let { "$it kbps" },
            state.audioFormat?.let { if (it.channelCount >= 2) "Stereo" else "Mono" },
        ).joinToString(" · ")
        if (audio.isNotEmpty()) Text(audio, style = dabText(20), color = c.muted, maxLines = 1)
        SignalBars(state.signal?.bars ?: 0)
    }
}

@Composable
private fun Artwork(state: RadioState, service: DabServiceInfo, slideshowEnabled: Boolean) {
    val c = Dab.colors
    val bytes = state.tunedMetadata?.slideshowJpegOrPng?.takeIf { slideshowEnabled && state.announcement == null }
    val slide by produceState<ImageBitmap?>(null, bytes) {
        value = bytes?.let { b -> withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() } }
    }
    val logo = rememberStationLogo(service)
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier
            .fillMaxHeight()
            .aspectRatio(1f)
            .clip(shape)
            .background(c.placeholder)
            .semantics { contentDescription = if (slide != null) "Slideshow image from ${service.label}" else "${service.label} logo" },
        contentAlignment = Alignment.Center,
    ) {
        val img = slide ?: logo
        if (img != null) {
            Image(img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            MonogramTile(service.serviceId, service.label, size = 160.dp, corner = 32.dp, fontSize = 64)
        }
    }
}

@Composable
private fun StationInfo(
    state: RadioState,
    service: DabServiceInfo,
    favourite: Boolean,
    presetSlot: Int?,
    playWhenReady: Boolean,
    radio: RadioController,
    actions: RadioActions,
    modifier: Modifier,
) {
    val c = Dab.colors
    Column(modifier) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                val announcement = state.announcement
                val kicker = if (announcement != null) {
                    "Traffic announcement · ${announcement.service.label}"
                } else {
                    listOfNotNull(presetSlot?.let { "Preset ${it + 1}" }, ProgrammeType.name(service.programmeType))
                        .ifEmpty { listOf(service.ensembleLabel) }
                        .joinToString(" · ")
                }
                Text(
                    kicker.uppercase(),
                    style = dabText(22, FontWeight.Bold, letterSpacing = 1.sp),
                    color = c.accentText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    service.label,
                    style = dabText(64, FontWeight.ExtraBold, letterSpacing = (-1.5).sp, lineHeight = 67.sp),
                    color = c.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            DabIconButton(
                icon = if (favourite) DabIcons.HeartFilled else DabIcons.HeartOutline,
                contentDescription = if (favourite) "Remove from favourites" else "Add to favourites",
                size = 88.dp,
                iconSize = 40.dp,
                background = c.btn,
                tint = if (favourite) c.accentText else c.muted,
            ) { radio.setFavourite(service.key, !favourite) }
        }
        val label = state.tunedMetadata?.dynamicLabel?.takeIf { it.isNotBlank() }
        if (label != null) {
            Text(
                label,
                style = dabText(30, FontWeight.SemiBold, lineHeight = 39.sp),
                color = c.sub,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
        Spacer(Modifier.weight(1f).heightIn(min = 16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val square = RoundedCornerShape(28.dp)
            DabIconButton(DabIcons.Previous, "Previous station", 112.dp, 48.dp, c.btn, c.text, shape = square) {
                radio.stationStep(-1)?.let(actions::play)
            }
            DabIconButton(DabIcons.SeekDown, "Seek down", 112.dp, 48.dp, c.btn, c.text, shape = square) {
                radio.ensembleStep(-1)?.let(actions::play)
            }
            val playing = playWhenReady && state.tunerState != TunerState.READY
            DabIconButton(
                if (playing) DabIcons.Pause else DabIcons.Play,
                if (playing) "Pause" else "Play",
                144.dp, 60.dp, c.accent, OnAccent,
            ) { actions.togglePlayPause() }
            DabIconButton(DabIcons.SeekUp, "Seek up", 112.dp, 48.dp, c.btn, c.text, shape = square) {
                radio.ensembleStep(1)?.let(actions::play)
            }
            DabIconButton(DabIcons.Next, "Next station", 112.dp, 48.dp, c.btn, c.text, shape = square) {
                radio.stationStep(1)?.let(actions::play)
            }
        }
    }
}

/** Shown instead of the station when no tuner is open, while scanning, or before a station is picked. */
@Composable
private fun TunerCard(
    state: RadioState,
    hasStations: Boolean,
    demoAvailable: Boolean,
    radio: RadioController,
    actions: RadioActions,
    onOpenStations: () -> Unit,
    modifier: Modifier,
) {
    val c = Dab.colors
    val scan = state.scan
    val (kicker, title, body) = when {
        state.tunerState == TunerState.FAILED -> Triple("Tuner", "The tuner stopped responding", "Unplug the DAB stick, plug it back in and connect again.")
        !state.tunerOpen -> Triple("Tuner", "No tuner connected", "Plug in the USB DAB stick, then connect to it.")
        state.tunerState == TunerState.INITIALISING -> Triple(state.tunerName ?: "Tuner", "Starting the tuner…", "This takes a few seconds.")
        scan != null -> Triple(
            "Full scan",
            scan.progress?.let { "Scanning block ${it.channelLabel}" } ?: "Starting scan…",
            "${scan.found} stations found so far. Audio pauses while scanning.",
        )
        !hasStations -> Triple(state.tunerName ?: "Tuner", "No stations yet", "Scan Band III to find the stations you can receive here.")
        else -> Triple(state.tunerName ?: "Tuner", "Pick a station", "Choose one from your stations or presets.")
    }
    Column(
        modifier
            .background(c.nowCard, RoundedCornerShape(28.dp))
            .dabBorder(3.dp, c.idleBorder, RoundedCornerShape(28.dp))
            .padding(40.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(kicker.uppercase(), style = dabText(20, FontWeight.Bold, letterSpacing = 1.sp), color = c.accentText, maxLines = 1)
        Text(title, style = dabText(48, FontWeight.ExtraBold, letterSpacing = (-1).sp), color = c.text, modifier = Modifier.padding(top = 12.dp), maxLines = 2)
        Text(body, style = dabText(24), color = c.sub, modifier = Modifier.padding(top = 12.dp).widthIn(max = 760.dp))
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            when {
                !state.tunerOpen -> {
                    PillButton("Connect tuner", c.accent, OnAccent, height = 88.dp, horizontalPadding = 36.dp, fontSize = 24, weight = FontWeight.ExtraBold, corner = 22.dp) {
                        actions.connectTuner()
                    }
                    if (demoAvailable) {
                        PillButton("Demo tuner", Color.Transparent, c.text, height = 88.dp, horizontalPadding = 36.dp, fontSize = 24,
                            weight = FontWeight.ExtraBold, corner = 22.dp, outline = c.outline) { actions.connectDemo() }
                    }
                }
                scan != null -> PillButton("Cancel scan", Color.Transparent, c.text, height = 88.dp, horizontalPadding = 36.dp,
                    fontSize = 24, weight = FontWeight.ExtraBold, corner = 22.dp, outline = c.outline) { radio.cancelScan() }
                state.tunerReady && !hasStations -> PillButton("Scan for stations", c.accent, OnAccent, height = 88.dp, horizontalPadding = 36.dp,
                    fontSize = 24, weight = FontWeight.ExtraBold, corner = 22.dp) { radio.startScan() }
                state.tunerReady -> PillButton("Open stations", c.accent, OnAccent, height = 88.dp, horizontalPadding = 36.dp,
                    fontSize = 24, weight = FontWeight.ExtraBold, corner = 22.dp, onClick = onOpenStations)
            }
        }
    }
}

@Composable
private fun PresetStrip(
    slots: List<String?>,
    stations: List<Station>,
    current: DabServiceInfo?,
    radio: RadioController,
    actions: RadioActions,
) {
    val c = Dab.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        for (slot in 0 until 6) {
            val key = slots.getOrNull(slot)
            val station = key?.let { k -> stations.firstOrNull { it.key == k } }
            val shape = RoundedCornerShape(20.dp)
            val selected = station != null && station.key == current?.key
            val base = Modifier.weight(1f).height(104.dp)
            val decorated = when {
                station == null -> base.dashedBorder(3.dp, c.emptyBorder, 20.dp)
                selected -> base.background(c.selectedBg, shape).dabBorder(3.dp, c.accent, shape)
                else -> base.background(c.nowCard, shape).dabBorder(3.dp, c.idleBorder, shape)
            }
            val saveCurrent = { current?.let { radio.setPreset(slot, it.key) } ?: Unit }
            Column(
                decorated
                    .pressable(
                        shape,
                        onClickLabel = if (station != null) "Play" else "Save current station",
                        onLongClickLabel = "Save current station",
                        onLongClick = if (current != null) saveCurrent else null,
                    ) { if (station != null) actions.play(station.info) else saveCurrent() }
                    .semantics {
                        contentDescription = if (station != null) "Preset ${slot + 1}, ${station.info.label}" else "Empty preset ${slot + 1}"
                    }
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                // A saved preset gives the slot number's line to the station name.
                if (station == null) {
                    Text("${slot + 1}", style = dabText(18, FontWeight.ExtraBold), color = c.muted)
                }
                Text(
                    station?.info?.label ?: "+ Save",
                    style = dabText(22, FontWeight.Bold, lineHeight = 26.sp),
                    color = if (station != null) c.text else c.muted,
                    maxLines = if (station != null) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
