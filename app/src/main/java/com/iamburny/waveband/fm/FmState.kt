package com.iamburny.waveband.fm

enum class FmBand { FM, AM }

/**
 * How the head unit's MCU encodes FM/AM state. FM frequencies are in 10 kHz units (8750 is
 * 87.50 MHz) and AM frequencies in kHz. Band numbers 0-2 are FM1-FM3 and 3-4 AM1-AM2, each with
 * six presets; the preset list holds them in that order.
 */
object FmCodec {

    const val PRESETS_PER_BAND = 6
    private val fmBands = 0..2
    private val amBands = 3..4

    /** Widest FM range any region uses (Russia's OIRT band starts at 65.8 MHz). */
    val FM_RANGE = 6500..10800
    /** Long and medium wave. */
    val AM_RANGE = 144..1710

    fun bandOf(bandNumber: Int): FmBand? = when (bandNumber) {
        in fmBands -> FmBand.FM
        in amBands -> FmBand.AM
        else -> null
    }

    fun isValid(band: FmBand, frequency: Int): Boolean = frequency in when (band) {
        FmBand.FM -> FM_RANGE
        FmBand.AM -> AM_RANGE
    }

    /** Such as "98.10 MHz" or "1089 kHz". */
    fun label(band: FmBand, frequency: Int): String = when (band) {
        FmBand.FM -> "%d.%02d MHz".format(frequency / 100, frequency % 100)
        FmBand.AM -> "$frequency kHz"
    }

    /** The six preset frequencies of [bandNumber], or none if the list doesn't cover it. */
    fun presetsOf(bandNumber: Int, list: List<Int>): List<Int> {
        val start = bandNumber * PRESETS_PER_BAND
        return if (bandOf(bandNumber) == null || start + PRESETS_PER_BAND > list.size) emptyList()
        else list.subList(start, start + PRESETS_PER_BAND)
    }
}

/**
 * Key of an FM or AM frequency where a DAB station key would go, such as in a preset slot or the
 * saved station names: "fm:8950" or "am:909". DAB keys never contain a colon.
 */
object FmKey {

    fun of(band: FmBand, frequency: Int): String = "${band.name.lowercase()}:$frequency"

    /** The band and frequency of [key], or null if it isn't a valid FM/AM key. */
    fun parse(key: String): Pair<FmBand, Int>? {
        val band = when (key.substringBefore(':', "")) {
            "fm" -> FmBand.FM
            "am" -> FmBand.AM
            else -> return null
        }
        val frequency = key.substringAfter(':').toIntOrNull() ?: return null
        return if (FmCodec.isValid(band, frequency)) band to frequency else null
    }
}

/**
 * What the head unit's FM/AM tuner reports. [connected] means the vendor service is bound and
 * answered sanity checks; [active] means the tuner currently has the speakers, and [ours] that
 * we put it there and still hold it (the head unit's own radio app can also play it).
 */
data class FmState(
    val connected: Boolean = false,
    val active: Boolean = false,
    val ours: Boolean = false,
    val bandNumber: Int = 0,
    val frequency: Int = 0,
    /** Preset slot (0-5) the frequency was recalled from, -1 if none. */
    val presetSlot: Int = -1,
    /** RDS programme service name, blank if none. */
    val stationName: String = "",
    val stereo: Boolean = false,
    val trafficProgramme: Boolean = false,
    val trafficAnnouncements: Boolean = false,
    val rds: Boolean = false,
    val alternativeFrequencies: Boolean = false,
    val local: Boolean = false,
    val scanning: Boolean = false,
    /** Presets of all bands, see [FmCodec]. */
    val presets: List<Int> = emptyList(),
) {
    val band: FmBand? get() = FmCodec.bandOf(bandNumber)

    val frequencyLabel: String? get() = band?.let { FmCodec.label(it, frequency) }

    val bandPresets: List<Int> get() = FmCodec.presetsOf(bandNumber, presets)

    /** [FmKey] of the tuned frequency, or null while the band or frequency is unknown. */
    val key: String? get() = band?.takeIf { FmCodec.isValid(it, frequency) }?.let { FmKey.of(it, frequency) }
}
