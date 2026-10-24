package com.matt.dabtuner.backend.omri

import android.util.Log
import com.matt.dabtuner.backend.DabBackend
import com.matt.dabtuner.backend.PcmFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Decodes the encoded frames a service delivers on native threads, on a thread of its own, and
 * hands PCM to the backend listener. The listener's blocking PCM write paces this thread; if it
 * falls behind, the oldest queued frames are dropped so playback stays live.
 */
internal class AudioPipeline(private val listener: DabBackend.Listener) {

    private data class Config(val codec: Int, val channels: Int, val sampleRate: Int, val sbr: Boolean, val ps: Boolean)

    private class Frame(val data: ByteArray, val config: Config, val generation: Int)

    private val queue = ArrayBlockingQueue<Frame>(QUEUE_CAPACITY)
    @Volatile private var running = true
    @Volatile private var generation = 0

    // Owned by the decoder thread.
    private var decoder: AudioFrameDecoder? = null
    private var decoderConfig: Config? = null
    private var pcmFormat: PcmFormat? = null
    private var openFailed = false

    // Declared last so the thread never sees fields before their initialisers ran.
    private val thread = Thread(::run, "DabAudioDecoder").apply { start() }

    fun submit(frame: ByteArray, codec: Int, channels: Int, sampleRate: Int, sbr: Boolean, ps: Boolean) {
        if (!running) return
        val item = Frame(frame, Config(codec, channels, sampleRate, sbr, ps), generation)
        while (!queue.offer(item)) {
            queue.poll()
        }
    }

    /** Drops queued audio and resets the decoder, e.g. when switching services. */
    fun flush() {
        generation++
        queue.clear()
    }

    fun release() {
        running = false
        thread.interrupt()
        try {
            thread.join(JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        queue.clear()
    }

    private fun run() {
        var decodedGeneration = -1
        try {
            while (running) {
                val frame = queue.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                if (frame.generation != generation) continue
                if (frame.generation != decodedGeneration || frame.config != decoderConfig) {
                    closeDecoder()
                    // The PCM sink may have been recreated since the last service, so announce the format again.
                    if (frame.generation != decodedGeneration) pcmFormat = null
                    decodedGeneration = frame.generation
                }
                if (decoder == null && openFailed) continue
                val d = decoder ?: openDecoder(frame.config) ?: continue
                try {
                    d.decode(frame.data, ::deliver)
                } catch (e: Exception) {
                    Log.w(TAG, "Decoder error, restarting it", e)
                    closeDecoder()
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            closeDecoder()
        }
    }

    private fun openDecoder(config: Config): AudioFrameDecoder? {
        val d = try {
            when (config.codec) {
                OmriNative.CODEC_AAC -> AacDecoder(config.sampleRate, config.channels, config.sbr, config.ps)
                OmriNative.CODEC_MP2 -> Mp2Decoder()
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not create a decoder for $config", e)
            null
        }
        decoder = d
        decoderConfig = config
        openFailed = d == null
        return d
    }

    private fun closeDecoder() {
        try {
            decoder?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Decoder release failed", e)
        }
        decoder = null
        decoderConfig = null
        openFailed = false
    }

    private fun deliver(format: PcmFormat, pcm: ByteArray, length: Int) {
        if (format != pcmFormat) {
            listener.onAudioFormatChanged(format)
            pcmFormat = format
        }
        listener.onPcm(pcm, length)
    }

    private companion object {
        const val TAG = "AudioPipeline"
        const val QUEUE_CAPACITY = 64
        const val POLL_MS = 200L
        const val JOIN_TIMEOUT_MS = 2000L
    }
}
