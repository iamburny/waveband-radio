package com.matt.dabtuner.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.matt.dabtuner.PcmSink
import com.matt.dabtuner.backend.PcmFormat

/** Streams 16-bit PCM from the backend's audio thread into an [AudioTrack]. */
class AudioTrackSink : PcmSink {

    private val lock = Any()
    private var track: AudioTrack? = null
    private var format: PcmFormat? = null

    @Volatile
    var playing: Boolean = false
        set(value) {
            field = value
            synchronized(lock) {
                track?.let { if (value) it.play() else it.pause().also { _ -> it.flush() } }
            }
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
                .also { if (playing) it.play() }
        }
    }

    /**
     * Blocking write, which paces the backend to real time. It runs outside [lock] so that pausing
     * from the main thread never waits on a full buffer; AudioTrack tolerates a concurrent
     * stop/release by returning an error code.
     */
    override fun write(buffer: ByteArray, length: Int) {
        val t = synchronized(lock) { track } ?: return
        if (playing) t.write(buffer, 0, length)
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
    }
}
