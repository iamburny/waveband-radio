package com.iamburny.waveband.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.iamburny.waveband.PcmSink
import com.iamburny.waveband.backend.PcmFormat

/** Streams 16-bit PCM from the backend's audio thread into an [AudioTrack], and copies it to [tap]. */
class AudioTrackSink(private val tap: SpectrumTap) : PcmSink {

    private val lock = Any()
    private var track: AudioTrack? = null
    private var format: PcmFormat? = null
    // Frames written to the track since it was created or flushed, to tell how much is still queued.
    // Guarded by lock; [flushes] tells a write that straddled a flush not to count itself.
    private var framesWritten = 0L
    private var flushes = 0

    /** 0..1; lowered while another app plays over the radio and the system can't duck for us. */
    @Volatile
    var volume: Float = 1f
        set(value) {
            field = value
            synchronized(lock) { track?.setVolume(value) }
        }

    @Volatile
    var playing: Boolean = false
        set(value) {
            field = value
            synchronized(lock) {
                track?.let {
                    if (value) {
                        it.play()
                    } else {
                        it.pause()
                        it.flush()
                        framesWritten = 0
                        flushes++
                    }
                }
            }
            if (!value) tap.clear()
        }

    override fun onFormat(format: PcmFormat) {
        synchronized(lock) {
            if (format == this.format && track != null) return
            releaseTrack()
            this.format = format
            val channelMask = if (format.channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            require(minBuffer > 0) { "Unsupported PCM format $format" }
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(format.sampleRate)
                        .setChannelMask(channelMask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also {
                    it.setVolume(volume)
                    if (playing) it.play()
                }
        }
    }

    /**
     * Blocking write, which paces the backend to real time. It runs outside [lock] so that pausing
     * from the main thread never waits on a full buffer; AudioTrack tolerates a concurrent
     * stop/release by returning an error code.
     */
    override fun write(buffer: ByteArray, length: Int) {
        val (t, flushesBefore) = synchronized(lock) { (track ?: return) to flushes }
        if (!playing) return
        val n = t.write(buffer, 0, length)
        if (n <= 0) return
        synchronized(lock) {
            // A pause, release or format change since the write leaves nothing to count.
            if (track !== t || flushes != flushesBefore) return
            val channels = t.channelCount
            framesWritten += n / (2 * channels)
            if (tap.active) {
                val played = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                val queued = (framesWritten - played).coerceIn(0L, t.bufferSizeInFrames.toLong().coerceAtLeast(0L))
                tap.write(buffer, n, channels, t.sampleRate, queued)
            }
        }
    }

    fun release() {
        synchronized(lock) { releaseTrack() }
    }

    private fun releaseTrack() {
        track?.run {
            stop()
            release()
        }
        track = null
        format = null
        framesWritten = 0
    }
}
