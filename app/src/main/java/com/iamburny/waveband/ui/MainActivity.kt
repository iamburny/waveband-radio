package com.iamburny.waveband.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.iamburny.waveband.BuildConfig
import com.iamburny.waveband.DabTunerApp
import com.iamburny.waveband.backend.DabServiceInfo
import com.iamburny.waveband.data.Source
import com.iamburny.waveband.data.Station
import com.iamburny.waveband.data.TunerOrigin
import com.iamburny.waveband.fm.FmBand
import com.iamburny.waveband.playback.DabPlaybackService
import com.iamburny.waveband.playback.DabPlayer
import com.iamburny.waveband.ui.theme.DabTheme
import com.iamburny.waveband.ui.theme.isNight
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@UnstableApi
class MainActivity : ComponentActivity() {

    private val radio by lazy { DabTunerApp.radio(this) }
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = controllerFuture?.takeIf { it.isDone && !it.isCancelled }?.let { runCatching { it.get() }.getOrNull() }
    private var playWhenReady by mutableStateOf(false)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            this@MainActivity.playWhenReady = playWhenReady
        }
    }

    private val actions = object : RadioActions {
        override fun play(service: DabServiceInfo) = withController { c ->
            c.setMediaItem(DabPlayer.mediaItemFor(service))
            // Coming from FM/AM the player isn't idle, but DAB may still need preparing.
            c.prepare()
            c.play()
        }

        override fun playDab() {
            lifecycleScope.launch {
                val service = radio.state.value.current ?: lastSavedStation()?.info
                if (service != null && radio.state.value.tunerOpen) {
                    play(service)
                } else {
                    // Nothing to play on DAB: just leave FM/AM.
                    val fm = DabTunerApp.from(this@MainActivity).headUnitRadio
                    if (fm.state.value.ours) fm.deactivate()
                    radio.setSource(Source.DAB)
                }
            }
        }

        override fun playFm(band: FmBand, frequency: Int?) {
            val fm = DabTunerApp.from(this@MainActivity).headUnitRadio
            val s = fm.state.value
            if (!s.connected) return
            radio.setSource(Source.FM)
            when {
                !s.ours -> fm.activate(band, frequency)
                frequency != null -> if (frequency != s.frequency || band != s.band) fm.tune(band, frequency)
                // The band key cycles the tuner's FM1-FM3 banks, so only press it to change band.
                band != s.band -> fm.selectBand(band)
            }
        }

        override fun togglePlayPause() = withController { c ->
            when {
                c.playbackState == Player.STATE_IDLE || !c.playWhenReady -> {
                    if (c.playbackState == Player.STATE_IDLE) c.prepare()
                    c.play()
                }
                else -> c.pause()
            }
        }

        override fun connectTuner() {
            if (!radio.connectFirstAvailable()) radio.reportError("No supported USB tuner found")
        }

        override fun connectDemo() = radio.connectSimulated()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by radio.settings.collectAsStateWithLifecycle()
            // Nothing is drawn until the saved display mode is known, so the theme never flashes.
            val s = settings ?: return@setContent
            val night = isNight(s.displayMode)
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !night
                    isAppearanceLightNavigationBars = !night
                }
            }
            DabTheme(night = night, accent = s.accent) {
                CompositionLocalProvider(LocalStationLogos provides DabTunerApp.from(this).stationLogos) {
                    DabApp(radio, s, playWhenReady, demoAvailable = BuildConfig.DEBUG, actions = actions)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                radio.tunerOpened.collect(::resumeLastStation)
            }
        }

        requestNotificationPermissionIfNeeded()
        if (!handleUsbIntent(intent) && savedInstanceState == null) autoConnect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, DabPlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture === future) {
                controller?.let {
                    it.addListener(playerListener)
                    playWhenReady = it.playWhenReady
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controller?.removeListener(playerListener)
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onStop()
    }

    /** Runs [block] once the media controller is connected; dropped if the activity stops first. */
    private fun withController(block: (MediaController) -> Unit) {
        val future = controllerFuture ?: return
        future.addListener({
            if (controllerFuture === future) controller?.let(block)
        }, ContextCompat.getMainExecutor(this))
    }

    /** On launch: opens an attached tuner, or in debug builds the demo tuner if that was used last. */
    private fun autoConnect() {
        if (radio.state.value.tunerOpen) return
        if (radio.tunerAttached()) {
            radio.connectFirstAvailable()
            return
        }
        if (!BuildConfig.DEBUG) return
        lifecycleScope.launch {
            val settings = radio.settings.first { it != null }!!
            if (settings.lastOrigin == TunerOrigin.DEMO && !radio.state.value.tunerOpen) radio.connectSimulated()
        }
    }

    /** Plays the station that was playing last, once a freshly opened tuner of the same origin is ready. */
    private suspend fun resumeLastStation(origin: TunerOrigin) {
        radio.consumeTunerOpened()
        if (radio.state.value.current != null) return
        val settings = radio.settings.first { it != null }!!
        // Listening to FM/AM: the tuner stays ready, but doesn't take over. The head unit's tuner may
        // still be connecting, and may still be playing from before the app was stopped.
        if (settings.source == Source.FM) return
        val key = settings.lastStationKey ?: return
        if (settings.lastOrigin != origin) return
        val station = radio.savedStation(origin, key) ?: return
        if (radio.state.value.current == null && radio.origin.value == origin) actions.play(station.info)
    }

    /** The station last played on the tuner whose stations are shown, if it is still saved. */
    private suspend fun lastSavedStation(): Station? {
        val settings = radio.settings.first { it != null }!!
        val origin = radio.origin.value ?: return null
        val key = settings.lastStationKey ?: return null
        if (settings.lastOrigin != origin) return null
        return radio.savedStation(origin, key)
    }

    /** Launched by the system when a matching tuner is plugged in; permission is already granted then. */
    private fun handleUsbIntent(intent: Intent?): Boolean {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return false
        val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return false
        radio.connect(device)
        return true
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
