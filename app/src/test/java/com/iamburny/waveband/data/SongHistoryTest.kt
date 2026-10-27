package com.iamburny.waveband.data

import com.iamburny.waveband.AnnouncementState
import com.iamburny.waveband.RadioState
import com.iamburny.waveband.backend.DabServiceInfo
import com.iamburny.waveband.backend.PlayingItem
import com.iamburny.waveband.backend.ServiceMetadata
import com.iamburny.waveband.backend.TunerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongHistoryTest {

    private val station = DabServiceInfo(serviceId = 0xC221, ensembleId = 0xC1CE, frequencyKHz = 222064, label = "Radio Test")
    private val song = PlayingItem(title = "Paper Boats", artist = "The Lanterns", running = true, toggle = false)

    private fun state(item: PlayingItem?, tuner: TunerState = TunerState.PLAYING, announcement: AnnouncementState? = null) =
        RadioState(tunerState = tuner, current = station, metadata = ServiceMetadata(station.key, item = item), announcement = announcement)

    @Test
    fun hearsATaggedSong() {
        assertEquals(SongHistory.Song(station.key, "Radio Test", "The Lanterns", "Paper Boats"), SongHistory.heard(state(song)))
    }

    @Test
    fun ignoresWhatIsNotAudiblyASong() {
        assertNull(SongHistory.heard(state(null)))
        assertNull(SongHistory.heard(state(song.copy(running = false))))
        assertNull(SongHistory.heard(state(song.copy(artist = null))))
        assertNull(SongHistory.heard(state(song.copy(title = null))))
        assertNull(SongHistory.heard(state(song, tuner = TunerState.READY)))
        assertNull(SongHistory.heard(state(song, announcement = AnnouncementState(1, station.copy(serviceId = 0xC222)))))
    }

    @Test
    fun ignoresMetadataOfAnotherStation() {
        val other = state(song).copy(metadata = ServiceMetadata("00000001@222064", item = song))
        assertNull(SongHistory.heard(other))
    }

    @Test
    fun newUnlessItWasTheLastSongOnThatStation() {
        val heard = SongHistory.Song(station.key, "Radio Test", "The Lanterns", "Paper Boats")
        fun last(artist: String, title: String) = SongPlayEntity(1, station.key, "Radio Test", artist, title, 0L)
        assertTrue(SongHistory.isNew(heard, null))
        assertFalse(SongHistory.isNew(heard, last("The Lanterns", "Paper Boats")))
        assertFalse(SongHistory.isNew(heard, last("THE LANTERNS", "paper boats")))
        assertTrue(SongHistory.isNew(heard, last("The Lanterns", "Slow Tide")))
    }

    @Test
    fun itemFromDlPlusTags() {
        val item = PlayingItem.fromTags(listOf(4, 1, 2), listOf(" The Lanterns ", "Paper Boats", "Harbour"), running = true, toggle = true)
        assertEquals(PlayingItem("Paper Boats", "The Lanterns", running = true, toggle = true), item)
        assertEquals(PlayingItem(null, null, running = false, toggle = false), PlayingItem.fromTags(listOf(1), listOf("  "), running = false, toggle = false))
    }
}
