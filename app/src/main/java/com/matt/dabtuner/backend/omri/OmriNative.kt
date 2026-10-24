package com.matt.dabtuner.backend.omri

import android.util.Log

/**
 * JNI entry points of libdabtuner_jni, which drives the omri-usb DAB stack (libomri.so) through
 * its C API. The library is loaded on first use; [load] reports whether that worked.
 */
object OmriNative {

    private const val TAG = "OmriNative"

    /** Native-to-Kotlin notifications. Called on native worker threads; implementations must not block. */
    interface Callbacks {
        /** One of [STATE_READY], [STATE_FAILED], [STATE_SCANNING]. */
        fun onState(state: Int)
        fun onScanProgress(channelIndex: Int, channelCount: Int)

        /**
         * Labels are UTF-8. [audioCodec] is [CODEC_MP2], [CODEC_AAC] or -1 for no audio.
         * [bitrateKbps] is 0 and [subchannelId] -1 when unknown. [announcementFlags] uses the
         * [com.matt.dabtuner.backend.AnnouncementType] bits; [clusterIds] holds unsigned bytes.
         */
        fun onServiceFound(
            serviceId: Long,
            ensembleId: Int,
            ensembleEcc: Int,
            frequencyKHz: Int,
            label: ByteArray,
            shortLabel: ByteArray,
            ensembleLabel: ByteArray,
            programmeType: Int,
            isProgrammeService: Boolean,
            audioCodec: Int,
            bitrateKbps: Int,
            subchannelId: Int,
            announcementFlags: Int,
            clusterIds: ByteArray,
        )
        fun onServiceStarted(serviceId: Long)
        fun onServiceStopped(serviceId: Long)

        /** [antennaLevel] is 0 (worst) to 6 (best). */
        fun onReception(locked: Boolean, antennaLevel: Int)

        /** One encoded frame: an MPEG Layer II frame or a DAB+ AAC access unit. */
        fun onAudio(frame: ByteArray, codec: Int, channels: Int, sampleRate: Int, sbr: Boolean, ps: Boolean)
        fun onDynamicLabel(utf8: ByteArray)
        fun onSlideshow(image: ByteArray, contentSubType: Int)

        /** Announcement support (FIG 0/18) of a service in the tuned ensemble, when first seen or changed. */
        fun onAnnouncementSupport(ensembleId: Int, serviceId: Long, flags: Int, clusterIds: ByteArray)

        /** Announcement switching (FIG 0/19) in the tuned ensemble; ends may repeat. */
        fun onAnnouncement(clusterId: Int, flags: Int, subchannelId: Int, active: Boolean)
    }

    const val STATE_READY = 0
    const val STATE_FAILED = 1
    const val STATE_SCANNING = 4

    const val CODEC_MP2 = 0
    const val CODEC_AAC = 63

    private val loaded: Boolean by lazy {
        try {
            System.loadLibrary("dabtuner_jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Could not load the native DAB backend", e)
            false
        }
    }

    fun load(): Boolean = loaded

    @JvmStatic external fun nativeSetLogging(enabled: Boolean)

    /**
     * Opens the tuner on a usbfs descriptor whose interface is already claimed. Returns 0 if the
     * device does not answer; [infoOut] (size >= 1) receives a diagnostic either way.
     */
    @JvmStatic external fun nativeOpen(fd: Int, deviceName: String, callbacks: Callbacks, infoOut: Array<String?>): Long

    /** Stops every native thread; no callback runs after this returns. */
    @JvmStatic external fun nativeClose(handle: Long)
    @JvmStatic external fun nativeStartScan(handle: Long)
    @JvmStatic external fun nativeStopScan(handle: Long)
    @JvmStatic external fun nativeStartService(handle: Long, frequencyKHz: Int, ensembleId: Int, ensembleEcc: Int, serviceId: Long)
    @JvmStatic external fun nativeStopService(handle: Long)
}
