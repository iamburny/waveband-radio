package com.iamburny.waveband.backend.omri

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.iamburny.waveband.backend.PcmFormat
import java.io.Closeable

/** Decodes encoded audio frames from one service into 16-bit PCM. */
internal interface AudioFrameDecoder : Closeable {
    /** [sink] receives PCM in its first argument's format; the buffer is reused after it returns. */
    fun decode(frame: ByteArray, sink: (PcmFormat, ByteArray, Int) -> Unit)
}

/**
 * DAB+ (HE-AAC v2, 960-sample frames) decoder on the platform's AAC MediaCodec. DAB+ carries
 * raw access units without in-band configuration, so the AudioSpecificConfig is built from the
 * superframe header fields. A software codec is preferred because hardware AAC decoders often
 * lack the 960-sample frame length.
 */
internal class AacDecoder(
    private val sampleRate: Int,
    channels: Int,
    sbr: Boolean,
    ps: Boolean,
) : AudioFrameDecoder {

    private val codec: MediaCodec
    private val bufferInfo = MediaCodec.BufferInfo()
    private var pcm = ByteArray(16 * 1024)
    private var outputFormat: PcmFormat? = null
    private var presentationUs = 0L

    init {
        val outputChannels = if (ps) 2 else channels
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, outputChannels).apply {
            setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(audioSpecificConfig(sampleRate, channels, sbr, ps)))
            setInteger(MediaFormat.KEY_IS_ADTS, 0)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
        codec = createDecoder()
        try {
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    override fun decode(frame: ByteArray, sink: (PcmFormat, ByteArray, Int) -> Unit) {
        val inIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (inIndex >= 0) {
            val input = codec.getInputBuffer(inIndex)
            if (input != null && input.capacity() >= frame.size) {
                input.clear()
                input.put(frame)
                codec.queueInputBuffer(inIndex, 0, frame.size, presentationUs, 0)
            } else {
                codec.queueInputBuffer(inIndex, 0, 0, presentationUs, 0)
            }
            presentationUs += FRAME_SAMPLES * 1_000_000L / sampleRate
        }
        drain(sink)
    }

    private fun drain(sink: (PcmFormat, ByteArray, Int) -> Unit) {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    outputFormat = PcmFormat(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                }
                outIndex >= 0 -> {
                    val output = codec.getOutputBuffer(outIndex)
                    val size = bufferInfo.size
                    val format = outputFormat ?: codec.outputFormat.let {
                        PcmFormat(it.getInteger(MediaFormat.KEY_SAMPLE_RATE), it.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    }.also { outputFormat = it }
                    if (output != null && size > 0) {
                        if (pcm.size < size) pcm = ByteArray(size)
                        output.position(bufferInfo.offset)
                        output.get(pcm, 0, size)
                        codec.releaseOutputBuffer(outIndex, false)
                        sink(format, pcm, size)
                    } else {
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                }
                else -> return
            }
        }
    }

    override fun close() {
        try {
            codec.stop()
        } catch (_: IllegalStateException) {
        }
        codec.release()
    }

    companion object {
        private const val INPUT_TIMEOUT_US = 20_000L
        private const val FRAME_SAMPLES = 960

        private val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000)

        private fun createDecoder(): MediaCodec {
            val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                !info.isEncoder &&
                    info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true) } &&
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isSoftwareOnly
                    else info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android."))
            }
            return if (software != null) MediaCodec.createByCodecName(software.name)
            else MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        }

        /**
         * ISO/IEC 14496-3 AudioSpecificConfig for a DAB+ stream. [sampleRate] is the output (DAC)
         * rate; with SBR the AAC core runs at half of it and SBR/PS are signalled explicitly.
         * The GASpecificConfig sets frameLengthFlag for 960-sample frames.
         */
        fun audioSpecificConfig(sampleRate: Int, channels: Int, sbr: Boolean, ps: Boolean): ByteArray {
            val bits = BitWriter()
            val coreRate = if (sbr) sampleRate / 2 else sampleRate
            val channelConfig = if (ps) 1 else channels.coerceIn(1, 2)
            if (sbr) {
                bits.write(if (ps) 29 else 5, 5)
                bits.write(rateIndex(coreRate), 4)
                bits.write(channelConfig, 4)
                bits.write(rateIndex(sampleRate), 4)
                bits.write(2, 5)
            } else {
                bits.write(2, 5)
                bits.write(rateIndex(coreRate), 4)
                bits.write(channelConfig, 4)
            }
            bits.write(1, 1) // frameLengthFlag: 960 samples
            bits.write(0, 1) // dependsOnCoreCoder
            bits.write(0, 1) // extensionFlag
            return bits.toByteArray()
        }

        private fun rateIndex(rate: Int): Int {
            val index = SAMPLE_RATES.indexOf(rate)
            require(index >= 0) { "Unsupported AAC sample rate $rate" }
            return index
        }
    }

    private class BitWriter {
        private val bytes = java.io.ByteArrayOutputStream()
        private var current = 0
        private var used = 0

        fun write(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                current = (current shl 1) or ((value shr i) and 1)
                if (++used == 8) {
                    bytes.write(current)
                    current = 0
                    used = 0
                }
            }
        }

        fun toByteArray(): ByteArray {
            if (used > 0) bytes.write(current shl (8 - used))
            return bytes.toByteArray()
        }
    }
}
