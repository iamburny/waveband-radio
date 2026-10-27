package com.iamburny.waveband.backend.omri

/**
 * The share of a service's audio frames lost in reception. Frames that fail their checks are never
 * delivered, so the meter compares how many arrive over [windowNanos] with how many the codec
 * produces in that time. Thread-safe; times are [System.nanoTime] values.
 */
internal class FrameLossMeter(private val windowNanos: Long = 5_000_000_000L) {

    private var windowStart = NONE
    private var lastFrame = NONE
    private var frames = 0
    private var rate = 0.0
    private var loss: Float? = null

    @Synchronized
    fun reset() {
        windowStart = NONE
        lastFrame = NONE
        frames = 0
        loss = null
    }

    /** One frame arrived at [now], of a stream that carries [framesPerSecond]. */
    @Synchronized
    fun onFrame(framesPerSecond: Double, now: Long) {
        lastFrame = now
        if (windowStart == NONE || framesPerSecond != rate) {
            rate = framesPerSecond
            windowStart = now
            frames = 0
            return
        }
        frames++
        val elapsed = now - windowStart
        if (elapsed >= windowNanos) {
            val expected = rate * elapsed / 1e9
            loss = (1.0 - frames / expected).coerceIn(0.0, 1.0).toFloat()
            windowStart = now
            frames = 0
        }
    }

    /** The loss over the last full window; everything once frames have stopped arriving. Null before the first window. */
    @Synchronized
    fun lossAt(now: Long): Float? = if (lastFrame != NONE && now - lastFrame > windowNanos) 1f else loss

    companion object {
        private const val NONE = Long.MIN_VALUE

        /** Frames per second of a DAB+ (AAC, 960 samples at the core rate) or DAB (MP2, 1152 samples) stream. */
        fun framesPerSecond(codec: Int, sampleRate: Int, sbr: Boolean): Double = when (codec) {
            OmriNative.CODEC_AAC -> (if (sbr) sampleRate / 2 else sampleRate) / 960.0
            else -> sampleRate / 1152.0
        }
    }
}
