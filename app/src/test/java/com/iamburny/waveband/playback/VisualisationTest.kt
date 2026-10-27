package com.iamburny.waveband.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VisualisationTest {

    private val rate = 48_000

    private fun sine(hz: Double, amplitude: Double, length: Int, phase: Double = 0.0) =
        FloatArray(length) { (amplitude * sin(2 * PI * hz * it / rate + phase)).toFloat() }

    @Test
    fun scopeStartsAtTheSamePointOfARepeatingWave() {
        // Whatever the phase, the trace starts on a rising zero crossing.
        for (phase in listOf(0.0, 1.0, 2.5, 4.0)) {
            val wave = sine(440.0, 0.5, 2048, phase)
            val start = ScopeTrigger.find(wave, 1024)
            assertTrue("phase $phase", wave[start - 1] < 0f && wave[start] >= 0f)
        }
    }

    @Test
    fun scopeWithoutACrossingStartsAtTheBeginning() {
        assertEquals(0, ScopeTrigger.find(FloatArray(2048), 1024))
        assertEquals(0, ScopeTrigger.find(FloatArray(2048) { 0.3f }, 1024))
    }

    @Test
    fun vuReadsZeroAtTheReferenceLevel() {
        val meter = VuMeter(referenceDb = -14f)
        // A sine 14 dB below full scale.
        val tone = sine(1_000.0, 0.1995, 2048)
        repeat(60) { meter.step(tone, 1f / 60) }
        assertEquals(VuMeter.position(0f), meter.needle, 0.01f)
        assertFalse(meter.peak)
    }

    @Test
    fun vuNeedleTakesAbout300Milliseconds() {
        val meter = VuMeter()
        val tone = sine(1_000.0, 0.1995, 2048)
        meter.step(tone, 0.1f)
        assertTrue(meter.needle < VuMeter.position(0f) * 0.95f)
        meter.step(tone, 0.2f)
        assertEquals(VuMeter.position(0f), meter.needle, 0.01f)
    }

    @Test
    fun vuPeakLightHoldsThenGoesOut() {
        val meter = VuMeter(peakHoldS = 0.8f)
        meter.step(sine(1_000.0, 0.99, 2048), 0.02f)
        assertTrue(meter.peak)
        meter.step(null, 0.5f)
        assertTrue(meter.peak)
        repeat(30) { meter.step(null, 0.1f) }
        assertFalse(meter.peak)
        assertTrue(meter.settled)
    }

    @Test
    fun vuScaleEnds() {
        assertEquals(0f, VuMeter.position(VuMeter.MIN_VU), 1e-4f)
        assertEquals(1f, VuMeter.position(VuMeter.MAX_VU), 1e-4f)
        assertEquals(0f, VuMeter.position(-40f), 0f)
    }

    @Test
    fun bandsRiseFastFallSlowAndSettle() {
        val smoother = BandSmoother(1)
        smoother.step(floatArrayOf(1f), 0.1f)
        assertEquals(1f, smoother.levels[0], 1e-4f)
        smoother.step(floatArrayOf(0f), 0.1f)
        assertEquals(1f - 0.14f, smoother.levels[0], 1e-4f)
        assertTrue(smoother.peaks[0] > smoother.levels[0])
        repeat(50) { smoother.step(floatArrayOf(0f), 0.1f) }
        assertTrue(smoother.settled)
    }

    @Test
    fun tapKeepsTheChannelsApart() {
        val tap = SpectrumTap(capacity = 64)
        // Four stereo frames: left 0.5, right -0.25.
        val pcm = ByteArray(16)
        for (f in 0 until 4) {
            put(pcm, f * 2, 16384)
            put(pcm, f * 2 + 1, -8192)
        }
        tap.write(pcm, pcm.size, channels = 2, rate = rate, queuedFrames = 0)
        val left = FloatArray(4)
        val right = FloatArray(4)
        assertEquals(rate, tap.read(left, right))
        assertArrayEquals(FloatArray(4) { 0.5f }, left, 1e-4f)
        assertArrayEquals(FloatArray(4) { -0.25f }, right, 1e-4f)
        val mono = FloatArray(4)
        assertEquals(rate, tap.read(mono))
        assertArrayEquals(FloatArray(4) { 0.125f }, mono, 1e-4f)
    }

    private fun put(pcm: ByteArray, index: Int, value: Int) {
        pcm[index * 2] = value.toByte()
        pcm[index * 2 + 1] = (value shr 8).toByte()
    }
}
