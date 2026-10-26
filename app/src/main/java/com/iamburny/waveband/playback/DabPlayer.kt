package com.iamburny.waveband.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.iamburny.waveband.RadioController
import com.iamburny.waveband.backend.DabServiceInfo
import com.iamburny.waveband.backend.TunerState
import com.iamburny.waveband.data.PresetKind
import com.iamburny.waveband.data.QuickPresets
import com.iamburny.waveband.data.Source
import com.iamburny.waveband.fm.FmBand
import com.iamburny.waveband.fm.FmKey
import com.iamburny.waveband.fm.FmState
import com.iamburny.waveband.fm.HeadUnitRadio

/** What the session shows: the radio's current station and its live metadata. */
data class NowPlaying(
    val tunerState: TunerState,
    val station: DabServiceInfo?,
    val dynamicLabel: String?,
    /** JPEG or PNG: the slideshow image, or the station's monogram. */
    val artwork: ByteArray?,
) {
    // Artwork is compared by identity: the radio hands over a new array for every new image.
    override fun equals(other: Any?): Boolean =
        other is NowPlaying && tunerState == other.tunerState && station == other.station &&
            dynamicLabel == other.dynamicLabel && artwork === other.artwork

    override fun hashCode(): Int = ((tunerState.hashCode() * 31 + station.hashCode()) * 31 + dynamicLabel.hashCode()) * 31 +
        System.identityHashCode(artwork)
}

/** What the session shows while the head unit's FM/AM tuner is the source. */
data class FmNowPlaying(
    val state: FmState,
    /** PNG tile showing the band and frequency. */
    val artwork: ByteArray?,
) {
    override fun equals(other: Any?): Boolean = other is FmNowPlaying && state == other.state && artwork === other.artwork

    override fun hashCode(): Int = state.hashCode() * 31 + System.identityHashCode(artwork)
}

/**
 * Media3 [Player] for the radio's current source. For DAB it plays a live service: playback
 * commands become [RadioController] calls, and audio is pushed by the backend into
 * [AudioTrackSink], not pulled by this player. Skip next/previous (media buttons, steering-wheel
 * controls) step through the saved stations. A prepared, playing station holds media audio focus
 * ([updateAudio]): losing it for good pauses, losing it for a while (calls, prompts) suppresses
 * the sound until it comes back.
 *
 * For FM/AM it mirrors the head unit's tuner ([fm]): play and pause put the tuner on the speakers
 * and take it off, and skip steps through the saved presets of the band playing. The tuner holds its own audio focus,
 * so switching to it pauses DAB through the usual focus loss, and playing DAB again switches it off.
 */
@UnstableApi
class DabPlayer(
    context: Context,
    looper: Looper,
    private val radio: RadioController,
    private val sink: AudioTrackSink,
    private val fm: HeadUnitRadio,
) : SimpleBasePlayer(looper) {

    private var item: MediaItem? = null
    private var wantPlay = false
    private var playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
    private val focus = AudioFocus(context, looper, ::onFocusChanged)
    private var prepared = false
    private var nowPlaying = NowPlaying(TunerState.CLOSED, null, null, null)
    private var fmNow = FmNowPlaying(FmState(), null)
    private val handler = Handler(looper)
    // Play or pause asked of the tuner and not yet confirmed by it; reported meanwhile so the
    // controls don't bounce back while the tuner switches.
    private var fmWant: Boolean? = null
    // The preset a recent skip tuned to, until the tuner has had time to report it.
    private var skipTarget: String? = null
    private var skipTargetUntil = 0L
    private val forgetFmWant = Runnable {
        fmWant = null
        invalidateState()
    }
    private var error: PlaybackException? = null

    /** Whether FM/AM is the source; a tuner that isn't there leaves DAB in charge. */
    private val onFm: Boolean get() = radio.source.value == Source.FM && fmNow.state.connected

    /** Called by the service when the radio's state changes. */
    fun onNowPlayingChanged(now: NowPlaying) {
        nowPlaying = now
        if (now.tunerState == TunerState.FAILED || now.tunerState == TunerState.CLOSED) prepared = false
        // The radio can change station by itself (resuming after a scan, or via the app's own UI).
        val station = now.station
        if (station != null && station.key != item?.mediaId) item = mediaItemFor(station)
        updateAudio()
        invalidateState()
    }

    /** Called by the service when the head unit tuner's state changes. */
    fun onFmChanged(now: FmNowPlaying) {
        // Whoever switched the tuner on (the app's own screen included), it is now the source.
        if (now.state.ours && !fmNow.state.ours) radio.setSource(Source.FM)
        if (!now.state.ours && fmNow.state.ours && fmWant != false) {
            playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
        }
        if (fmWant == now.state.ours) fmWant = null
        fmNow = now
        invalidateState()
    }

    /**
     * Called by the service when the radio switches between DAB and FM/AM. Posted, as the switch
     * can happen inside one of this player's own handlers.
     */
    fun onSourceChanged() {
        handler.post { invalidateState() }
    }

    override fun getState(): State {
        if (onFm) return fmState()
        val current = item
        val builder = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(wantPlay, playWhenReadyReason)
            .setPlaybackSuppressionReason(
                if (wantPlay && focus.lentOut) Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
                else Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            )
            .setPlayerError(error)
        if (current == null) {
            return builder.setPlaybackState(Player.STATE_IDLE).build()
        }
        val station = nowPlaying.station?.takeIf { it.key == current.mediaId }
        val label = nowPlaying.dynamicLabel?.takeIf { station != null && it.isNotBlank() }
        val metadata = current.mediaMetadata.buildUpon()
            .setDisplayTitle(current.mediaMetadata.title)
            .setArtist(label ?: current.mediaMetadata.albumTitle)
            .setSubtitle(label)
            .apply {
                val art = nowPlaying.artwork
                if (station != null && art != null) setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            }
            .build()
        val itemData = MediaItemData.Builder(current.mediaId)
            .setMediaItem(current)
            .setMediaMetadata(metadata)
            .setIsDynamic(true)
            // Seekable so that "previous" reaches handleSeek; no seek-in-item command is offered.
            .setIsSeekable(true)
            .setDurationUs(C.TIME_UNSET)
            .setLiveConfiguration(MediaItem.LiveConfiguration.UNSET)
            .build()
        val playbackState = when {
            !prepared || error != null -> Player.STATE_IDLE
            nowPlaying.tunerState == TunerState.PLAYING -> Player.STATE_READY
            else -> Player.STATE_BUFFERING
        }
        return builder
            .setPlaylist(listOf(itemData))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(playbackState)
            .build()
    }

    private fun fmState(): State {
        val f = fmNow.state
        val band = f.band ?: FmBand.FM
        val frequency = f.frequencyLabel
        val name = f.stationName.ifBlank { null }
        val title = name ?: frequency ?: band.name
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .setArtist(listOfNotNull(band.name, frequency.takeIf { name != null }).joinToString(" · "))
            .setStation(title)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .apply { fmNow.artwork?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
            .build()
        val itemData = MediaItemData.Builder(FM_MEDIA_ID)
            .setMediaItem(fmMediaItem())
            .setMediaMetadata(metadata)
            .setIsDynamic(true)
            .setIsSeekable(true)
            .setDurationUs(C.TIME_UNSET)
            .setLiveConfiguration(MediaItem.LiveConfiguration.UNSET)
            .build()
        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(fmWant ?: f.ours, playWhenReadyReason)
            .setPlaylist(listOf(itemData))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(Player.STATE_READY)
            .build()
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val index = if (startIndex == C.INDEX_UNSET) 0 else startIndex
        val newItem = mediaItems.getOrNull(index)
        if (newItem?.mediaId == FM_MEDIA_ID) {
            radio.setSource(Source.FM)
            return Futures.immediateVoidFuture()
        }
        if (newItem != null) {
            // Switch the tuner off at once rather than wait for DAB audio to take it over.
            if (fmNow.state.ours) fm.deactivate()
            radio.setSource(Source.DAB)
        }
        val changed = newItem?.mediaId != item?.mediaId || newItem?.mediaId != radio.state.value.current?.key
        item = newItem
        if (changed && prepared) tuneCurrent()
        updateAudio()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (onFm || (prepared && error == null)) return Futures.immediateVoidFuture()
        prepared = item != null
        tuneCurrent()
        updateAudio()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
        if (onFm) {
            if (playWhenReady != (fmWant ?: fmNow.state.ours)) {
                if (playWhenReady) fm.activate() else fm.deactivate()
                fmWant = playWhenReady
                handler.removeCallbacks(forgetFmWant)
                handler.postDelayed(forgetFmWant, FM_SWITCH_TIMEOUT_MS)
            }
            return Futures.immediateVoidFuture()
        }
        wantPlay = playWhenReady
        updateAudio()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val direction = when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT -> 1
            Player.COMMAND_SEEK_TO_PREVIOUS -> -1
            else -> return Futures.immediateVoidFuture()
        }
        if (onFm) {
            skipFm(direction)
            return Futures.immediateVoidFuture()
        }
        val target = radio.stationStep(direction) ?: return Futures.immediateVoidFuture()
        item = mediaItemFor(target)
        if (!prepared) prepared = true
        tuneCurrent()
        updateAudio()
        return Futures.immediateVoidFuture()
    }

    /**
     * Tunes to the next (+1) or previous (-1) of the presets for the band being listened to, or
     * steps through the tuner's own presets when none are saved for it.
     */
    fun skipFm(direction: Int) {
        val f = fmNow.state
        val band = f.band
        if (band == null) {
            fm.stepPreset(direction)
            return
        }
        // Presses quicker than the tuner reports back step on from the last one.
        val from = skipTarget?.takeIf { SystemClock.uptimeMillis() < skipTargetUntil } ?: f.key
        val key = QuickPresets.step(radio.presets.value, PresetKind.of(band), from, direction)
        val target = key?.let(FmKey::parse)
        if (target != null) {
            skipTarget = key
            skipTargetUntil = SystemClock.uptimeMillis() + SKIP_SETTLE_MS
            fm.tune(target.first, target.second)
        } else {
            fm.stepPreset(direction)
        }
    }

    override fun handleStop(): ListenableFuture<*> {
        if (onFm) {
            fmWant = false
            fm.deactivate()
        }
        prepared = false
        error = null
        updateAudio()
        radio.stopPlayback()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        handler.removeCallbacksAndMessages(null)
        prepared = false
        updateAudio()
        radio.stopPlayback()
        return Futures.immediateVoidFuture()
    }

    private fun tuneCurrent() {
        val current = item ?: return
        val service = radio.findService(current.mediaId)
        error = when {
            service == null -> PlaybackException("Unknown station ${current.mediaId}", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
            !radio.state.value.tunerOpen -> PlaybackException("No tuner connected", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            else -> null
        }
        if (error == null && service != null) radio.play(service)
    }

    /**
     * Holds focus exactly while a prepared station should play, and makes the sink follow it:
     * silent while focus is lent out, quieter while ducked. A refused request (for example during
     * a phone call) leaves the radio paused.
     */
    private fun updateAudio() {
        if (wantPlay && prepared && error == null && !focus.request()) wantPlay = false
        val active = wantPlay && prepared && error == null
        if (!active) focus.abandon()
        val audible = active && !focus.lentOut
        val volume = if (focus.ducked) DUCK_VOLUME else 1f
        if (sink.volume != volume) sink.volume = volume
        if (sink.playing != audible) sink.playing = audible
    }

    private fun onFocusChanged(lostForGood: Boolean) {
        if (lostForGood && wantPlay) {
            wantPlay = false
            playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
        }
        updateAudio()
        invalidateState()
    }

    companion object {
        private const val DUCK_VOLUME = 0.2f
        private const val FM_SWITCH_TIMEOUT_MS = 2_000L
        private const val SKIP_SETTLE_MS = 1_500L

        /** Media ID of the head unit's FM/AM tuner, as opposed to a DAB station's key. */
        const val FM_MEDIA_ID = "headunit:fm"

        private val COMMANDS = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_RELEASE,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
            )
            .build()

        fun fmMediaItem(): MediaItem =
            MediaItem.Builder()
                .setMediaId(FM_MEDIA_ID)
                .setUri("headunit://fm")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("FM/AM radio")
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                        .build()
                )
                .build()

        fun mediaItemFor(service: DabServiceInfo): MediaItem =
            MediaItem.Builder()
                .setMediaId(service.key)
                .setUri("dab://${service.key}")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(service.label)
                        .setAlbumTitle(service.ensembleLabel)
                        .setStation(service.label)
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                        .build()
                )
                .build()
    }
}
