package com.iamburny.waveband.backend.omri

import com.iamburny.waveband.backend.PcmFormat

/**
 * MPEG-1/2 Audio Layer II decoder for classic DAB services, backed by libmpg123. Android's
 * platform decoders only handle Layer III, so this cannot go through MediaCodec.
 * Not thread-safe; use from one thread.
 */
internal class Mp2Decoder : AudioFrameDecoder {

    private var handle: Long = run {
        check(OmriNative.load()) { "Native DAB library is not available" }
        nativeCreate().also { check(it != 0L) { "mpg123 could not be initialised" } }
    }
    private val output = ByteArray(32 * 1024)
    private val format = IntArray(2)

    override fun decode(frame: ByteArray, sink: (PcmFormat, ByteArray, Int) -> Unit) {
        if (handle == 0L) return
        val length = nativeDecode(handle, frame, frame.size, output, format)
        if (length > 0 && format[0] > 0 && format[1] > 0) {
            sink(PcmFormat(format[0], format[1]), output, length)
        }
    }

    override fun close() {
        if (handle != 0L) {
            nativeRelease(handle)
            handle = 0L
        }
    }

    companion object {
        @JvmStatic external fun nativeCreate(): Long
        @JvmStatic external fun nativeDecode(handle: Long, input: ByteArray, inputLength: Int, output: ByteArray, format: IntArray): Int
        @JvmStatic external fun nativeRelease(handle: Long)
    }
}
