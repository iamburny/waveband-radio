package com.iamburny.waveband.data

import com.iamburny.waveband.RadioState
import com.iamburny.waveband.backend.TunerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The songs heard on DAB, from the DL Plus tags of stations that send them. A song is logged once
 * it has played for [SETTLE_MS], so flicking through stations doesn't fill the list, and only if it
 * isn't the song last logged on that station, so retuning mid-song doesn't log it twice.
 */
@OptIn(FlowPreview::class)
class SongHistory(private val dao: SongPlayDao, state: Flow<RadioState>, private val scope: CoroutineScope, private val clock: () -> Long = System::currentTimeMillis) {

    /** Newest first. */
    val plays: StateFlow<List<SongPlayEntity>> = dao.observe().stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            state
                .map(::heard)
                .distinctUntilChanged()
                .debounce(SETTLE_MS)
                .filterNotNull()
                .collect { song ->
                    withContext(Dispatchers.IO) {
                        if (isNew(song, dao.latest(song.stationKey))) {
                            dao.record(SongPlayEntity(stationKey = song.stationKey, stationLabel = song.stationLabel, artist = song.artist, title = song.title, playedAt = clock()), KEEP)
                        }
                    }
                }
        }
    }

    fun clear() {
        scope.launch(Dispatchers.IO) { dao.clear() }
    }

    /** A song as a station tags it. */
    data class Song(val stationKey: String, val stationLabel: String, val artist: String, val title: String)

    companion object {
        const val SETTLE_MS = 10_000L
        const val KEEP = 1000

        /** The song audibly playing on a DAB station in [state], when the station tags one. */
        fun heard(state: RadioState): Song? {
            if (state.tunerState != TunerState.PLAYING || state.announcement != null) return null
            val station = state.current ?: return null
            val item = state.tunedMetadata?.item?.takeIf { it.running } ?: return null
            val artist = item.artist ?: return null
            val title = item.title ?: return null
            return Song(station.key, station.label, artist, title)
        }

        /** Whether [song] differs from the [last] song logged on its station. */
        fun isNew(song: Song, last: SongPlayEntity?): Boolean =
            last == null || !last.artist.equals(song.artist, ignoreCase = true) || !last.title.equals(song.title, ignoreCase = true)
    }
}
