package com.iamburny.waveband.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iamburny.waveband.DabTunerApp
import com.iamburny.waveband.RadioController
import com.iamburny.waveband.RadioState
import com.iamburny.waveband.backend.BandIII
import com.iamburny.waveband.backend.TunerState
import com.iamburny.waveband.data.Accent
import com.iamburny.waveband.data.AppSettings
import com.iamburny.waveband.data.DisplayMode
import com.iamburny.waveband.ui.theme.Dab
import com.iamburny.waveband.ui.theme.OnAccent
import com.iamburny.waveband.ui.theme.colours
import com.iamburny.waveband.ui.theme.dabText

@Composable
fun SettingsScreen(
    radio: RadioController,
    settings: AppSettings,
    demoAvailable: Boolean,
    actions: RadioActions,
    onOpenLicences: () -> Unit,
) {
    val state by radio.state.collectAsStateWithLifecycle()
    val stations by radio.stations.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.height(64.dp), verticalAlignment = Alignment.CenterVertically) { ScreenTitle("Settings") }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ScanCard(state, stations.size, radio, actions)
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ToggleRow(
                    "Traffic announcements",
                    "Switches to bulletins on the current multiplex only",
                    settings.trafficAnnouncements,
                    radio::setTrafficAnnouncements,
                )
                ToggleRow("Slideshow images", "Show station artwork on Now Playing", settings.slideshow, radio::setSlideshow)
                ToggleRow(
                    "Station logos",
                    "Downloads logos from broadcasters over the internet",
                    settings.stationLogos,
                    radio::setStationLogos,
                )
                SettingRow("Display") {
                    Segmented {
                        for (mode in DisplayMode.entries) {
                            val on = settings.displayMode == mode
                            SegmentButton(mode.name.lowercase().replaceFirstChar { it.uppercase() }, on) { radio.setDisplayMode(mode) }
                        }
                    }
                }
                SettingRow("Accent colour") {
                    Segmented {
                        for (accent in Accent.entries) AccentSwatch(accent, settings.accent == accent) { radio.setAccent(accent) }
                    }
                }
                // Only one region exists so far, so the row shows it without leading anywhere.
                LinkRow("Region", settings.region.label, onClick = null)
                TunerRow(state, demoAvailable, radio, actions)
                HeadUnitTunerRows(settings, radio)
                LinkRow("Open-source licences", null, onOpenLicences)
            }
        }
    }
}

@Composable
private fun ScanCard(state: RadioState, saved: Int, radio: RadioController, actions: RadioActions) {
    val c = Dab.colors
    val shape = RoundedCornerShape(28.dp)
    val scan = state.scan
    Column(
        Modifier
            .width(440.dp)
            .fillMaxHeight()
            .background(c.card, shape)
            .dabBorder(2.dp, c.cardBorder, shape)
            .padding(32.dp),
    ) {
        Text("FULL SCAN", style = dabText(20, FontWeight.Bold, letterSpacing = 1.sp), color = c.accentText)
        val title = when {
            scan != null -> scan.progress?.let { "Scanning block ${it.channelLabel}" } ?: "Starting scan…"
            state.tunerState == TunerState.FAILED -> "Tuner not responding"
            !state.tunerOpen -> "No tuner connected"
            state.tunerState == TunerState.INITIALISING -> "Starting the tuner…"
            else -> "Band III"
        }
        Text(title, style = dabText(36, FontWeight.ExtraBold, lineHeight = 41.sp), color = c.text, modifier = Modifier.padding(top = 12.dp), maxLines = 2)
        val sub = when {
            scan != null -> "${scan.found} stations found so far"
            !state.tunerOpen -> "Connect the DAB stick to scan"
            else -> "$saved stations saved"
        }
        Text(sub, style = dabText(22), color = c.sub, modifier = Modifier.padding(top = 10.dp))
        if (scan != null) {
            val channels = BandIII.channels
            val progress = scan.progress?.let { ((it.channelIndex + 1) * 100 / it.channelCount.coerceAtLeast(1)).coerceIn(0, 100) } ?: 0
            Box(
                Modifier
                    .padding(top = 28.dp)
                    .fillMaxWidth()
                    .height(20.dp)
                    .clip(CircleShape)
                    .background(c.track)
                    .semantics {
                        contentDescription = "Scan progress"
                        progressBarRangeInfo = ProgressBarRangeInfo(progress / 100f, 0f..1f)
                    },
            ) {
                Box(Modifier.fillMaxWidth(progress / 100f).height(20.dp).clip(CircleShape).background(c.accent))
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(channels.first().first, style = dabText(18), color = c.muted)
                Text("$progress%", style = dabText(18), color = c.muted)
                Text(channels.last().first, style = dabText(18), color = c.muted)
            }
        }
        Spacer(Modifier.weight(1f))
        val buttonModifier = Modifier.fillMaxWidth()
        when {
            scan != null -> PillButton("Cancel scan", Color.Transparent, c.text, buttonModifier, height = 88.dp, fontSize = 24,
                weight = FontWeight.ExtraBold, corner = 22.dp, outline = c.outline) { radio.cancelScan() }
            !state.tunerOpen -> PillButton("Connect tuner", c.accent, OnAccent, buttonModifier, height = 88.dp, fontSize = 24,
                weight = FontWeight.ExtraBold, corner = 22.dp) { actions.connectTuner() }
            else -> PillButton("Start full scan", c.accent, OnAccent, buttonModifier, height = 88.dp, fontSize = 24,
                weight = FontWeight.ExtraBold, corner = 22.dp, enabled = state.tunerReady) { radio.startScan() }
        }
        Text(
            "Audio pauses while scanning",
            style = dabText(18),
            color = c.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        )
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String? = null, trailing: @Composable () -> Unit) {
    val c = Dab.colors
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .background(c.card, shape)
            .dabBorder(2.dp, c.cardBorder, shape)
            .padding(start = 28.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = dabText(26, FontWeight.Bold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = dabText(18), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
        trailing()
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(title, subtitle) { DabSwitch(checked, title, onChange) }
}

@Composable
private fun Segmented(content: @Composable () -> Unit) {
    Row(
        Modifier.background(Dab.colors.segBg, CircleShape).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun SegmentButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Dab.colors
    Box(
        Modifier
            .height(60.dp)
            .background(if (selected) c.invBg else Color.Transparent, CircleShape)
            .pressable(CircleShape, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = dabText(22, FontWeight.Bold), color = if (selected) c.invFg else c.segFg)
    }
}

@Composable
private fun AccentSwatch(accent: Accent, selected: Boolean, onClick: () -> Unit) {
    val name = accent.name.lowercase().replaceFirstChar { it.uppercase() }
    Box(
        Modifier
            .size(60.dp)
            .background(accent.colours().first, CircleShape)
            .pressable(CircleShape, onClick = onClick)
            .semantics {
                contentDescription = "$name accent"
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(DabIcons.Check, contentDescription = null, tint = OnAccent, modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun LinkRow(title: String, value: String?, onClick: (() -> Unit)?) {
    val c = Dab.colors
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .background(c.card, shape)
            .dabBorder(2.dp, c.cardBorder, shape)
            .then(if (onClick != null) Modifier.pressable(shape, onClick = onClick) else Modifier)
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = dabText(26, FontWeight.Bold), color = c.text, modifier = Modifier.weight(1f), maxLines = 1)
        if (value != null) Text(value, style = dabText(22), color = c.muted, maxLines = 1)
        if (onClick != null) Icon(DabIcons.Chevron, contentDescription = null, tint = c.muted, modifier = Modifier.size(32.dp))
    }
}

/** Settings of the head unit's own FM/AM tuner, on head units that have one. */
@Composable
private fun HeadUnitTunerRows(settings: AppSettings, radio: RadioController) {
    val fm = DabTunerApp.from(LocalContext.current).headUnitRadio
    val fmState by fm.state.collectAsStateWithLifecycle()
    if (!fmState.connected) return
    ToggleRow("FM and AM radio", "Use the head unit's own tuner alongside DAB", settings.headUnitTuner) { enabled ->
        if (!enabled && fmState.ours) fm.deactivate()
        radio.setHeadUnitTuner(enabled)
    }
    if (!settings.headUnitTuner) return
    val (local, toggleLocal) = rememberTunerToggle(fmState.local) { fm.toggleLocal() }
    ToggleRow("Local stations only", "Seek skips weaker stations; off is DX", local) { toggleLocal() }
    val (mono, toggleMono) = rememberTunerToggle(fmState.mono) { fm.toggleMono() }
    ToggleRow("FM in mono", "Can reduce hiss on weak stations", mono) { toggleMono() }
    fmState.rdsEnabled?.let { reported ->
        val (rds, toggleRds) = rememberTunerToggle(reported) { fm.setRdsEnabled(!reported) }
        ToggleRow("RDS", "Station names and traffic information on FM", rds) { toggleRds() }
    }
    fmState.regionLabel?.let { LinkRow("FM/AM region", it, onClick = null) }
}

@Composable
private fun TunerRow(state: RadioState, demoAvailable: Boolean, radio: RadioController, actions: RadioActions) {
    val c = Dab.colors
    val status = when (state.tunerState) {
        TunerState.CLOSED -> "Not connected"
        TunerState.INITIALISING -> "Starting"
        TunerState.READY -> "Ready"
        TunerState.SCANNING -> "Scanning"
        TunerState.PLAYING -> "Playing"
        TunerState.FAILED -> "Not responding"
    }
    val detail = listOfNotNull(state.tunerName, state.backendName?.let { "backend $it" }, status).joinToString(" · ")
    SettingRow("Tuner", detail) {
        if (state.tunerState == TunerState.CLOSED || state.tunerState == TunerState.FAILED) {
            if (demoAvailable) PillButton("Demo", c.btn, c.text) { actions.connectDemo() }
            PillButton("Connect", c.btn, c.text) { actions.connectTuner() }
        } else {
            PillButton("Disconnect", c.btn, c.text) { radio.disconnect() }
        }
    }
}
