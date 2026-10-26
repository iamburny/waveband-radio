package com.iamburny.waveband.data

import com.iamburny.waveband.fm.FmBand
import com.iamburny.waveband.fm.FmKey

/** What a preset holds: a DAB station, or an FM or AM frequency. */
enum class PresetKind {
    DAB, FM, AM;

    companion object {
        fun of(key: String): PresetKind = when (FmKey.parse(key)?.first) {
            FmBand.FM -> FM
            FmBand.AM -> AM
            null -> DAB
        }

        fun of(band: FmBand): PresetKind = if (band == FmBand.FM) FM else AM
    }
}

/**
 * The quick presets under Now Playing: the first [COUNT] presets of the kind being listened to,
 * in the Presets page's order.
 */
object QuickPresets {
    const val COUNT = 6

    /**
     * The preset slot behind each of the [COUNT] quick presets: the first presets of [kind], then
     * free slots that a station can be saved into, then null once no slots are free.
     */
    fun slots(presets: List<String?>, kind: PresetKind): List<Int?> {
        val held = presets.indices.filter { slot -> presets[slot]?.let { PresetKind.of(it) == kind } == true }
        val free = presets.indices.filter { presets[it] == null }
        val slots = (held.take(COUNT) + free).take(COUNT)
        return slots + List(COUNT - slots.size) { null }
    }

    /**
     * The preset of [kind] after (+1) or before (-1) [currentKey], wrapping round; the first or
     * last one when [currentKey] isn't a preset. A station saved twice is visited once. Null when
     * there are no presets of [kind].
     */
    fun step(presets: List<String?>, kind: PresetKind, currentKey: String?, direction: Int): String? {
        val keys = presets.filterNotNull().filter { PresetKind.of(it) == kind }.distinct()
        if (keys.isEmpty()) return null
        val index = keys.indexOf(currentKey)
        return when {
            index >= 0 -> keys[Math.floorMod(index + direction, keys.size)]
            direction > 0 -> keys.first()
            else -> keys.last()
        }
    }
}
