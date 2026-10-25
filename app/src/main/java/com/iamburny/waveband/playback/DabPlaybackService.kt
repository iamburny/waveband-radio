package com.iamburny.waveband.playback

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.iamburny.waveband.DabTunerApp
import com.iamburny.waveband.Monogram
import com.iamburny.waveband.R
import com.iamburny.waveband.RadioController
import com.iamburny.waveband.backend.DabServiceInfo
import com.iamburny.waveband.logos.RadioDns
import com.iamburny.waveband.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Foreground media playback service. Media3 promotes it to the foreground (with the media
 * notification) while the player is playing, and exposes it to system UI, Bluetooth and
 * other controllers through the [MediaSession].
 */
@UnstableApi
class DabPlaybackService : MediaSessionService() {

    private lateinit var radio: RadioController
    private lateinit var sink: AudioTrackSink
    private lateinit var player: DabPlayer
    private var session: MediaSession? = null
    private var monogramCache: Pair<String, ByteArray>? = null
    private var logoCache: Pair<String, ByteArray>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        radio = DabTunerApp.radio(this)
        sink = AudioTrackSink()
        radio.pcmSink = sink
        player = DabPlayer(Looper.getMainLooper(), radio, sink)

        val openUi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openUi)
            .setCallback(SessionCallback())
            .build()

        val logos = DabTunerApp.from(this).stationLogos
        scope.launch {
            combine(radio.state, radio.settings, logos.available) { state, settings, available ->
                val station = state.current
                val metadata = state.tunedMetadata
                val slide = metadata?.slideshowJpegOrPng?.takeIf { settings?.slideshow != false && state.announcement == null }
                NowPlaying(
                    tunerState = state.tunerState,
                    station = station,
                    dynamicLabel = metadata?.dynamicLabel,
                    artwork = slide ?: station?.let { logoArtwork(it, available) ?: monogramArtwork(it) },
                )
            }
                // Reading the logo and rendering the monogram are image work; keep them off the main thread.
                .flowOn(Dispatchers.Default)
                .distinctUntilChanged()
                .collect(player::onNowPlayingChanged)
        }
    }

    /**
     * PNG of the station's downloaded logo, or null if there is none. The same array is returned
     * while the logo is unchanged, so the session metadata is not republished on every state change.
     */
    private suspend fun logoArtwork(station: DabServiceInfo, available: Map<String, Long>): ByteArray? {
        val key = RadioDns.logoKey(station) ?: return null
        val version = available[key] ?: return null
        logoCache?.let { (cachedKey, png) -> if (cachedKey == "$key@$version") return png }
        val png = DabTunerApp.from(this).stationLogos.png(key) ?: return null
        logoCache = "$key@$version" to png
        return png
    }

    /** PNG of the station's monogram tile, cached for the current station. Called on a background thread. */
    private fun monogramArtwork(station: DabServiceInfo): ByteArray {
        monogramCache?.let { (key, png) -> if (key == station.key) return png }
        val size = 320
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Monogram.colour(station.serviceId))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 128f
            typeface = ResourcesCompat.getFont(this@DabPlaybackService, R.font.manrope_extrabold)
        }
        val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(Monogram.initials(station.label), size / 2f, baseline, paint)
        val png = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        bitmap.recycle()
        monogramCache = station.key to png
        return png
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        radio.pcmSink = null
        sink.release()
        super.onDestroy()
    }

    /** Resolves media IDs sent by controllers (which arrive without a URI) to playable items. */
    private inner class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.mapNotNull { item ->
                radio.findService(item.mediaId)?.let(DabPlayer::mediaItemFor)
            }.toMutableList()
            if (resolved.isEmpty()) {
                return Futures.immediateFailedFuture(IllegalArgumentException("Unknown station; scan first"))
            }
            return Futures.immediateFuture(resolved)
        }
    }
}
