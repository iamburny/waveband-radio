package com.iamburny.waveband.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpectrumAnalyserTest {

    private val analyser = SpectrumAnalyser()
    private val rate = 48_000

    private fun sine(hz: Double, amplitude: Double = 1.0) =
        FloatArray(analyser.fftSize) { (amplitude * sin(2 * PI * hz * it / rate)).toFloat() }

    private fun levels(samples: FloatArray) = FloatArray(analyser.bands).also { analyser.analyse(samples, rate, it) }

    @Test
    fun silenceIsTheFloor() {
        assertTrue(levels(FloatArray(analyser.fftSize)).all { it == 0f })
    }

    @Test
    fun aToneLightsItsOwnBand() {
        for (hz in listOf(100.0, 1_000.0, 8_000.0)) {
            val out = levels(sine(hz))
            val loudest = out.indices.maxBy { out[it] }
            assertEquals("$hz Hz", analyser.bandOf(hz), loudest)
            assertEquals("$hz Hz at full scale", 1f, out[loudest], 0.05f)
        }
    }

    @Test
    fun quieterIsLower() {
        val band = analyser.bandOf(1_000.0)
        val full = levels(sine(1_000.0))[band]
        val quiet = levels(sine(1_000.0, amplitude = 0.01))[band]
        // 40 dB down on a 70 dB scale.
        assertEquals(full - 40f / 70f, quiet, 0.05f)
    }

    @Test
    fun bandsOutsideTheRange() {
        assertEquals(-1, analyser.bandOf(20.0))
        assertEquals(-1, analyser.bandOf(20_000.0))
        assertEquals(0, analyser.bandOf(40.0))
        assertEquals(analyser.bands - 1, analyser.bandOf(15_999.0))
    }
}
