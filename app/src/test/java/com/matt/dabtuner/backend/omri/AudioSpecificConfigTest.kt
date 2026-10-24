package com.matt.dabtuner.backend.omri

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSpecificConfigTest {

    private fun asc(rate: Int, channels: Int, sbr: Boolean, ps: Boolean) =
        AacDecoder.audioSpecificConfig(rate, channels, sbr, ps).joinToString("") { "%02X".format(it) }

    @Test
    fun aacLc48kStereoUses960SampleFrames() {
        // AOT 2, 48 kHz (index 3), 2 channels, frameLengthFlag 1.
        assertEquals("1194", asc(48000, 2, sbr = false, ps = false))
    }

    @Test
    fun aacLc32kMono() {
        // AOT 2, 32 kHz (index 5), 1 channel, frameLengthFlag 1.
        assertEquals("128C", asc(32000, 1, sbr = false, ps = false))
    }

    @Test
    fun heAacSignalsSbrExplicitlyWithHalfRateCore() {
        // AOT 5, core 24 kHz (6), 2 channels, extension 48 kHz (3), AOT 2, frameLengthFlag 1.
        assertEquals("2B118A00", asc(48000, 2, sbr = true, ps = false))
    }

    @Test
    fun heAacV2SignalsParametricStereoOnMonoCore() {
        // AOT 29, core 24 kHz (6), 1 channel, extension 48 kHz (3), AOT 2, frameLengthFlag 1.
        assertEquals("EB098A00", asc(48000, 1, sbr = true, ps = true))
    }
}
