package com.matt.dabtuner.playback

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.matt.dabtuner.RadioController
import com.matt.dabtuner.backend.DabServiceInfo
import com.matt.dabtuner.backend.TunerState

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

/**
 * Media3 [Player] for a live DAB service. Playback commands become [RadioController] calls;
 * audio itself is pushed by the backend into [AudioTrackSink], not pulled by this player.
 * Skip next/previous (media buttons, steering-wheel controls) step through the saved stations.
 */
@UnstableApi
class DabPlayer(
    looper: Looper,
    private val radio: RadioController,
    private val sink: AudioTrackSink,
) : SimpleBasePlayer(looper) {

    private var item: MediaItem? = null
    private var wantPlay = false
    private var prepared = false
    private var nowPlaying = NowPlaying(TunerState.CLOSED, null, null, null)
    private var error: PlaybackException? = null

    /** Called by the service when the radio's state changes. */
    fun onNowPlayingChanged(now: NowPlaying) {
        nowPlaying = now
        if (now.tunerState == TunerState.FAILED || now.tunerState == TunerState.CLOSED) prepared = false
        // The radio can change station by itself (resuming after a scan, or via the app's own UI).
        val station = now.station
        if (station != null && station.key != item?.mediaId) item = mediaItemFor(station)
        invalidateState()
    }

    override fun getState(): State {
        val commands = Player.Commands.Builder()
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
        val current = item
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(wantPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
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

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val index = if (startIndex == C.INDEX_UNSET) 0 else startIndex
        val newItem = mediaItems.getOrNull(index)
        val changed = newItem?.mediaId != item?.mediaId || newItem?.mediaId != radio.state.value.current?.key
        item = newItem
        if (changed && prepared) tuneCurrent()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (prepared && error == null) return Futures.immediateVoidFuture()
        prepared = item != null
        tuneCurrent()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        wantPlay = playWhenReady
        sink.playing = playWhenReady
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val direction = when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT -> 1
            Player.COMMAND_SEEK_TO_PREVIOUS -> -1
            else -> return Futures.immediateVoidFuture()
        }
        val target = radio.stationStep(direction) ?: return Futures.immediateVoidFuture()
        item = mediaItemFor(target)
        if (!prepared) prepared = true
        tuneCurrent()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        prepared = false
        error = null
        sink.playing = false
        radio.stopPlayback()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        sink.playing = false
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

    companion object {
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
