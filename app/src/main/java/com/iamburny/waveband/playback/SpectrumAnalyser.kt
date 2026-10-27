package com.iamburny.waveband.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns a window of mono audio into [bands] levels between 0 and 1: a Hann-windowed FFT of
 * [fftSize] samples, read in log-spaced bands from [lowHz] to [highHz] on a dB scale where
 * [floorDb] is 0 and a full-scale sine is 1. Not thread-safe; one instance per view.
 */
class SpectrumAnalyser(
    val bands: Int = 32,
    val fftSize: Int = 2048,
    private val lowHz: Double = 40.0,
    private val highHz: Double = 16_000.0,
    private val floorDb: Double = -70.0,
) {
    init {
        require(fftSize >= 2 && fftSize and (fftSize - 1) == 0) { "fftSize must be a power of two" }
    }

    private val window = DoubleArray(fftSize) { 0.5 - 0.5 * cos(2 * PI * it / (fftSize - 1)) }
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    private val magnitudes = DoubleArray(fftSize / 2)

    /** Writes the level of each band of [samples] (at least [fftSize] long) at [sampleRate] into [out]. */
    fun analyse(samples: FloatArray, sampleRate: Int, out: FloatArray) {
        for (i in 0 until fftSize) {
            re[i] = samples[i] * window[i]
            im[i] = 0.0
        }
        fft()
        // A Hann-windowed sine of amplitude 1 peaks at fftSize / 4.
        val scale = 4.0 / fftSize
        for (k in magnitudes.indices) magnitudes[k] = sqrt(re[k] * re[k] + im[k] * im[k]) * scale

        val binHz = sampleRate.toDouble() / fftSize
        val ratio = highHz / lowHz
        for (b in 0 until bands) {
            val from = lowHz * ratio.pow(b.toDouble() / bands)
            val to = lowHz * ratio.pow((b + 1).toDouble() / bands)
            val first = (from / binHz).toInt().coerceIn(1, magnitudes.size - 1)
            val last = (to / binHz).toInt().coerceIn(first, magnitudes.size - 1)
            var peak = 0.0
            for (k in first..last) peak = max(peak, magnitudes[k])
            val db = if (peak > 0) 20 * log10(peak) else floorDb
            out[b] = ((db - floorDb) / -floorDb).coerceIn(0.0, 1.0).toFloat()
        }
    }

    /** In-place iterative radix-2 FFT of [re] and [im]. */
    private fun fft() {
        val n = fftSize
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2 * PI / length
            val wRe = cos(angle)
            val wIm = sin(angle)
            for (start in 0 until n step length) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until length / 2) {
                    val a = start + k
                    val b = a + length / 2
                    val tRe = re[b] * curRe - im[b] * curIm
                    val tIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
            }
            length = length shl 1
        }
    }

    /** The band that [hz] falls in, or -1 outside the analysed range. */
    fun bandOf(hz: Double): Int {
        if (hz < lowHz || hz >= highHz) return -1
        return (log10(hz / lowHz) / log10(highHz / lowHz) * bands).toInt().coerceIn(0, bands - 1)
    }
}
