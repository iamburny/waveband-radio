package com.iamburny.waveband.playback

/**
 * The most recent decoded audio, as left and right channels, for the visualisers. The sink writes
 * it on the audio thread while a view is [attach]ed; views read the frames that are leaving the
 * speaker now, which lag the newest written ones by what the AudioTrack still has buffered.
 */
class SpectrumTap(private val capacity: Int = 65_536) {

    private val lock = Any()
    private val left = FloatArray(capacity)
    private val right = FloatArray(capacity)
    // Guarded by lock.
    private var written = 0L
    private var queued = 0L
    private var writtenAt = 0L
    private var sampleRate = 0

    @Volatile private var viewers = 0

    /** Whether a view is showing the audio; the sink skips the copy otherwise. */
    val active: Boolean get() = viewers > 0

    fun attach() = synchronized(lock) { viewers++ }

    fun detach() = synchronized(lock) { viewers = (viewers - 1).coerceAtLeast(0) }

    /**
     * 16-bit interleaved [pcm] up to [length] that was just queued on the track, which now holds
     * [queuedFrames] frames not yet played. Mono audio goes to both channels; beyond two, only the
     * first two are kept.
     */
    fun write(pcm: ByteArray, length: Int, channels: Int, rate: Int, queuedFrames: Long) {
        val frames = length / (2 * channels)
        val second = if (channels > 1) 1 else 0
        synchronized(lock) {
            if (rate != sampleRate) {
                sampleRate = rate
                written = 0
            }
            for (f in 0 until frames) {
                val at = ((written + f) % capacity).toInt()
                left[at] = sample(pcm, f * channels) / 32768f
                right[at] = sample(pcm, f * channels + second) / 32768f
            }
            written += frames
            queued = queuedFrames
            writtenAt = System.nanoTime()
        }
    }

    /** Forgets the audio, e.g. when playback pauses and the track is flushed. */
    fun clear() = synchronized(lock) {
        written = 0
        queued = 0
    }

    /**
     * Fills [out] with the frames playing now and returns the sample rate, or 0 when not enough
     * audio is known, such as while paused. With [right], [out] gets the left channel; without, it
     * gets both mixed to mono.
     */
    fun read(out: FloatArray, right: FloatArray? = null): Int = synchronized(lock) {
        require(right == null || right.size == out.size)
        if (sampleRate == 0 || written == 0L) return 0
        val sinceWrite = (System.nanoTime() - writtenAt) * sampleRate / 1_000_000_000L
        // Nothing new for a while means playback has stopped.
        if (sinceWrite > sampleRate / 2 + queued) return 0
        val end = (written - queued + sinceWrite).coerceAtMost(written)
        val start = end - out.size
        if (start < 0 || start < written - capacity) return 0
        for (i in out.indices) {
            val at = ((start + i) % capacity).toInt()
            if (right == null) {
                out[i] = (left[at] + this.right[at]) / 2
            } else {
                out[i] = left[at]
                right[i] = this.right[at]
            }
        }
        sampleRate
    }

    private fun sample(pcm: ByteArray, index: Int): Int =
        (pcm[index * 2].toInt() and 0xFF) or (pcm[index * 2 + 1].toInt() shl 8)
}
