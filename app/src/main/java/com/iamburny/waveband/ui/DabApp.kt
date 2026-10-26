package com.iamburny.waveband.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iamburny.waveband.RadioController
import com.iamburny.waveband.backend.DabServiceInfo
import com.iamburny.waveband.data.AppSettings
import com.iamburny.waveband.fm.FmBand
import com.iamburny.waveband.ui.theme.Dab
import com.iamburny.waveband.ui.theme.OnAccent
import com.iamburny.waveband.ui.theme.dabText
import kotlinx.coroutines.delay

enum class Screen(val label: String, val icon: ImageVector) {
    PLAYING("Playing", DabIcons.Playing),
    STATIONS("Stations", DabIcons.Stations),
    PRESETS("Presets", DabIcons.Presets),
    SETTINGS("Settings", DabIcons.Settings),
}

/** Playback and tuner actions that go through the media session or need the activity. */
interface RadioActions {
    /** Tunes to [service] and starts playback through the media session. */
    fun play(service: DabServiceInfo)

    /** Switches back to DAB: the current or last station if the tuner can play it. */
    fun playDab()

    /** Listens to the head unit's tuner on [band], at [frequency] if given. */
    fun playFm(band: FmBand, frequency: Int? = null)
    fun togglePlayPause()
    fun connectTuner()
    fun connectDemo()
}

@Composable
fun DabApp(radio: RadioController, settings: AppSettings, playWhenReady: Boolean, demoAvailable: Boolean, actions: RadioActions) {
    val c = Dab.colors
    var screen by rememberSaveable { mutableStateOf(Screen.PLAYING) }
    var showLicences by rememberSaveable { mutableStateOf(false) }
    val state by radio.state.collectAsStateWithLifecycle()

    BackHandler(enabled = showLicences) { showLicences = false }
    BackHandler(enabled = !showLicences && screen != Screen.PLAYING) { screen = Screen.PLAYING }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        // The keyboard is left out of the insets: it covers the bottom of the screen instead of
        // shrinking the scaled layout.
        DesignScale(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
            Row(Modifier.fillMaxSize()) {
                NavRail(screen) {
                    showLicences = false
                    screen = it
                }
                Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 36.dp, vertical = 28.dp)) {
                    if (showLicences) {
                        LicencesScreen(onClose = { showLicences = false })
                    } else {
                        when (screen) {
                            Screen.PLAYING -> NowPlayingScreen(radio, settings, playWhenReady, demoAvailable, actions, onOpenStations = { screen = Screen.STATIONS })
                            Screen.STATIONS -> StationsScreen(radio, actions)
                            Screen.PRESETS -> PresetsScreen(radio, actions)
                            Screen.SETTINGS -> SettingsScreen(radio, settings, demoAvailable, actions, onOpenLicences = { showLicences = true })
                        }
                    }
                    state.lastError?.let { message ->
                        ErrorBanner(message, onDismiss = radio::clearError, modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }
}

@Composable
private fun NavRail(current: Screen, onSelect: (Screen) -> Unit) {
    val c = Dab.colors
    Column(
        Modifier
            .width(120.dp)
            .fillMaxHeight()
            .background(c.rail)
            .drawBehind {
                val line = c.railLine ?: return@drawBehind
                val w = 2.dp.toPx()
                drawLine(line, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w)
            }
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RailItem(Screen.PLAYING, current, onSelect)
        RailItem(Screen.STATIONS, current, onSelect)
        RailItem(Screen.PRESETS, current, onSelect)
        Spacer(Modifier.weight(1f))
        RailItem(Screen.SETTINGS, current, onSelect)
    }
}

@Composable
private fun RailItem(screen: Screen, current: Screen, onSelect: (Screen) -> Unit) {
    val c = Dab.colors
    val selected = screen == current
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .size(96.dp)
            .background(if (selected) c.accent else Color.Transparent, shape)
            .pressable(shape) { onSelect(screen) }
            .semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        val fg = if (selected) OnAccent else c.muted
        Icon(screen.icon, contentDescription = null, tint = fg, modifier = Modifier.size(36.dp))
        Text(screen.label, style = dabText(16, if (selected) FontWeight.Bold else FontWeight.SemiBold), color = fg, maxLines = 1)
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = Dab.colors
    LaunchedEffect(message) {
        delay(8_000)
        onDismiss()
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(c.invBg, RoundedCornerShape(22.dp))
            .padding(start = 28.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = dabText(22, FontWeight.SemiBold), color = c.invFg, modifier = Modifier.weight(1f), maxLines = 2)
        DabIconButton(DabIcons.Close, "Dismiss message", size = 64.dp, iconSize = 28.dp, background = Color.Transparent, tint = c.invFg, onClick = onDismiss)
    }
}
