package com.matt.dabtuner.backend.omri

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.matt.dabtuner.backend.PcmFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the audio decoders on the device with synthetic silent frames: libmpg123 for MPEG Layer II
 * and the platform AAC decoder with DAB+ (960-sample) configurations.
 */
@RunWith(AndroidJUnit4::class)
class NativeDecodersTest {

    @Test
    fun nativeLibraryLoads() {
        assertTrue(OmriNative.load())
    }

    @Test
    fun mp2Mpeg1At48kDecodesToStereoPcm() {
        // MPEG-1 Layer II, no CRC, 192 kbit/s, 48 kHz, stereo: 576-byte frames.
        val result = decodeMp2(silentMp2Frame(byteArrayOf(0xFF.toByte(), 0xFD.toByte(), 0xA4.toByte(), 0x00), 576))
        assertEquals(PcmFormat(48_000, 2), result.first)
        assertTrue("decoded ${result.second} bytes", result.second >= 1152 * 2 * 2)
    }

    @Test
    fun mp2Mpeg2LsfAt24kDecodesToStereoPcm() {
        // MPEG-2 LSF Layer II, no CRC, 64 kbit/s, 24 kHz, stereo: 144 * 64000 / 24000 = 384-byte frames.
        val result = decodeMp2(silentMp2Frame(byteArrayOf(0xFF.toByte(), 0xF5.toByte(), 0x84.toByte(), 0x00), 384))
        assertEquals(PcmFormat(24_000, 2), result.first)
        assertTrue("decoded ${result.second} bytes", result.second >= 1152 * 2 * 2)
    }

    @Test
    fun aacDecoderAcceptsEveryDabPlusConfiguration() {
        for (rate in intArrayOf(48_000, 32_000)) {
            for ((channels, sbr, ps) in listOf(Triple(1, false, false), Triple(2, false, false), Triple(2, true, false), Triple(1, true, false), Triple(1, true, true))) {
                AacDecoder(rate, channels, sbr, ps).close()
            }
        }
    }

    @Test
    fun aacLcMono960DecodesSilentFrames() {
        val decoder = AacDecoder(48_000, 1, sbr = false, ps = false)
        var format: PcmFormat? = null
        var bytes = 0
        try {
            repeat(50) {
                decoder.decode(silentAacMonoFrame()) { f, _, length -> format = f; bytes += length }
                Thread.sleep(5)
            }
        } finally {
            decoder.close()
        }
        assertEquals(48_000, format?.sampleRate)
        assertTrue("decoded $bytes bytes", bytes >= 960 * 2 * 10)
    }

    private fun decodeMp2(frame: ByteArray): Pair<PcmFormat?, Int> {
        val decoder = Mp2Decoder()
        var format: PcmFormat? = null
        var bytes = 0
        try {
            repeat(4) { decoder.decode(frame) { f, _, length -> format = f; bytes += length } }
        } finally {
            decoder.close()
        }
        return format to bytes
    }

    /** A Layer II frame whose bit allocations are all zero, i.e. digital silence. */
    private fun silentMp2Frame(header: ByteArray, length: Int): ByteArray =
        ByteArray(length).also { header.copyInto(it) }

    /**
     * Raw AAC-LC access unit: one single_channel_element with max_sfb = 0 (no spectral data),
     * followed by the END element.
     */
    private fun silentAacMonoFrame(): ByteArray {
        val bits = StringBuilder()
        fun put(value: Int, count: Int) = (count - 1 downTo 0).forEach { bits.append((value shr it) and 1) }
        put(0, 3)    // ID_SCE
        put(0, 4)    // element_instance_tag
        put(100, 8)  // global_gain
        put(0, 1)    // ics_reserved_bit
        put(0, 2)    // window_sequence: ONLY_LONG
        put(0, 1)    // window_shape
        put(0, 6)    // max_sfb
        put(0, 1)    // predictor_data_present
        put(0, 1)    // pulse_data_present
        put(0, 1)    // tns_data_present
        put(0, 1)    // gain_control_data_present
        put(7, 3)    // ID_END
        while (bits.length % 8 != 0) bits.append('0')
        return ByteArray(bits.length / 8) { i -> bits.substring(i * 8, i * 8 + 8).toInt(2).toByte() }
    }
}
