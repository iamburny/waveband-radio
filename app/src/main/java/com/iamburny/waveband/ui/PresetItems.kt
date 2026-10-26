package com.iamburny.waveband.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.iamburny.waveband.data.Station
import com.iamburny.waveband.fm.FmBand
import com.iamburny.waveband.fm.FmCodec
import com.iamburny.waveband.fm.FmKey
import com.iamburny.waveband.ui.theme.Dab
import com.iamburny.waveband.ui.theme.dabText

/** What a preset slot holds: a saved DAB station, or a frequency on the head unit's tuner. */
sealed interface PresetItem {
    val key: String
    val title: String

    data class DabStation(val station: Station) : PresetItem {
        override val key: String get() = station.key
        override val title: String get() = station.info.label
    }

    /** [available] is false while the head unit's tuner isn't connected; the preset is kept but can't play. */
    data class Tuner(val band: FmBand, val frequency: Int, val name: String?, val available: Boolean) : PresetItem {
        override val key: String get() = FmKey.of(band, frequency)
        val frequencyLabel: String get() = FmCodec.label(band, frequency)
        override val title: String get() = name ?: frequencyLabel
    }

    companion object {
        /**
         * The item behind preset [key]. FM/AM items can only play with [tunerAvailable], as only
         * head units with their own tuner have one; DAB stations must still be saved.
         */
        fun of(key: String?, stations: List<Station>, fmNames: Map<String, String>, tunerAvailable: Boolean): PresetItem? {
            if (key == null) return null
            FmKey.parse(key)?.let { (band, frequency) ->
                return Tuner(band, frequency, fmNames[key], tunerAvailable)
            }
            return stations.firstOrNull { it.key == key }?.let(::DabStation)
        }
    }
}

/** Square badge standing in for a station logo on an FM/AM preset. */
@Composable
fun BandTile(band: FmBand, size: Dp, corner: Dp, fontSize: Int, modifier: Modifier = Modifier) {
    val c = Dab.colors
    Box(modifier.size(size).background(c.btn, RoundedCornerShape(corner)), contentAlignment = Alignment.Center) {
        Text(band.name, style = dabText(fontSize, FontWeight.ExtraBold, letterSpacing = 0.5.sp), color = c.accentText, maxLines = 1)
    }
}
