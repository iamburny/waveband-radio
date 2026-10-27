package com.iamburny.waveband.playback

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Band levels that rise quickly and fall slowly, like a hi-fi's display, with peak caps that hold
 * the highest level and then drop after it.
 */
class BandSmoother(
    bands: Int,
    private val risePerS: Float = 30f,
    private val fallPerS: Float = 1.4f,
    private val peakFallPerS: Float = 0.4f,
) {
    val levels = FloatArray(bands)
    val peaks = FloatArray(bands)

    /** Whether everything has fallen to zero. */
    val settled: Boolean get() = levels.all { it == 0f } && peaks.all { it == 0f }

    /** Moves towards [target] over [dt] seconds. */
    fun step(target: FloatArray, dt: Float) {
        for (b in levels.indices) {
            levels[b] = if (target[b] > levels[b]) {
                levels[b] + (target[b] - levels[b]) * min(1f, dt * risePerS)
            } else {
                max(target[b], levels[b] - dt * fallPerS)
            }
            peaks[b] = if (levels[b] >= peaks[b]) levels[b] else max(0f, peaks[b] - dt * peakFallPerS)
        }
    }
}

/** Where an oscilloscope starts its trace, so that a steady wave stands still from frame to frame. */
object ScopeTrigger {

    /**
     * The first rising zero crossing within the first [search] samples after the lowest point in
     * the first half of them, which lands on the same part of a repeating wave each time; failing
     * that, the first rising crossing at all; failing that, 0. Looking for the lowest point only
     * in the first half leaves room for the crossing after it.
     */
    fun find(samples: FloatArray, search: Int): Int {
        val end = min(search, samples.size)
        var lowest = 0
        for (i in 1 until end / 2) if (samples[i] < samples[lowest]) lowest = i
        val after = risingCrossing(samples, lowest + 1, end)
        return if (after >= 0) after else risingCrossing(samples, 1, end).coerceAtLeast(0)
    }

    private fun risingCrossing(samples: FloatArray, from: Int, end: Int): Int {
        for (i in from.coerceAtLeast(1) until end) if (samples[i - 1] < 0f && samples[i] >= 0f) return i
        return -1
    }
}

/**
 * One channel of a VU meter. The needle follows the RMS level with the classic 300 ms ballistics,
 * and [referenceDb] reads 0 VU; the peak light comes on when a sample reaches [peakDb] and stays on
 * for [peakHoldS]. Levels are in dB relative to a full-scale sine.
 */
class VuMeter(
    private val referenceDb: Float = -14f,
    private val peakDb: Float = -1f,
    private val peakHoldS: Float = 0.8f,
) {
    private var rms = 0f
    private var peakLeft = 0f

    /** The needle's position across the scale, 0 at the left stop and 1 at the right. */
    var needle = 0f
        private set

    val peak: Boolean get() = peakLeft > 0f

    /** Whether the needle is at rest and the light is off. */
    val settled: Boolean get() = needle == 0f && !peak

    /** Takes in [samples] (the latest audio, or null for silence) over [dt] seconds. */
    fun step(samples: FloatArray?, dt: Float) {
        var sum = 0.0
        var top = 0f
        if (samples != null) {
            for (s in samples) {
                sum += s * s
                top = max(top, abs(s))
            }
        }
        val now = if (samples == null || samples.isEmpty()) 0f else sqrt(sum / samples.size).toFloat()
        rms += (now - rms) * (1f - exp(-dt / TIME_CONSTANT_S))
        if (rms < SILENT_RMS) rms = 0f
        needle = if (rms == 0f) 0f else position(dbOf(rms * SQRT2) - referenceDb)
        peakLeft = if (top > 0f && dbOf(top) >= peakDb) peakHoldS else max(0f, peakLeft - dt)
    }

    companion object {
        const val MIN_VU = -20f
        const val MAX_VU = 3f

        /** Where [vu] sits on the scale: a real VU meter's needle moves with voltage, not decibels. */
        fun position(vu: Float): Float {
            val low = 10f.pow(MIN_VU / 20)
            val high = 10f.pow(MAX_VU / 20)
            return ((10f.pow(vu / 20) - low) / (high - low)).coerceIn(0f, 1f)
        }

        private fun dbOf(amplitude: Float) = 20 * log10(amplitude)

        // A first-order filter with this time constant reaches 99% in 300 ms.
        private const val TIME_CONSTANT_S = 0.065f
        private const val SILENT_RMS = 1e-5f
        private const val SQRT2 = 1.4142135f
    }
}
