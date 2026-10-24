package com.matt.dabtuner.backend

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

/**
 * A DAB/DAB+ receiver implementation: tuner control, ensemble scanning, service decoding and
 * metadata extraction. Concrete implementations wrap a decoding stack (native or Kotlin);
 * the rest of the app only talks to this interface.
 *
 * Calls may block and must not be made on the main thread. [Listener] callbacks arrive on
 * backend-owned threads; [Listener.onPcm] in particular is on the audio thread and must return quickly.
 */
interface DabBackend {

    /** Human-readable name shown in diagnostics, e.g. "Stub" or "omri-usb". */
    val name: String

    /** Returns true if this backend can drive [device] (normally a VID/PID check plus probing). */
    fun supports(device: UsbDevice): Boolean

    /**
     * Takes ownership of the tuner. For [TunerSource.Usb] the caller has already been granted USB
     * permission and opened the connection; the backend claims interfaces it needs and may use
     * [TunerSource.Usb.fileDescriptor] directly (usbdevfs ioctls or libusb_wrap_sys_device).
     */
    fun open(source: TunerSource, listener: Listener): Boolean

    /** Stops all activity and releases the tuner. The [UsbDeviceConnection] is closed by the caller. */
    fun close()

    /** Scans the configured band and reports services through [Listener.onServiceFound]. */
    fun startScan(band: DabBand = DabBand.BAND_III)

    fun stopScan()

    /** Tunes to the ensemble carrying [service] and starts decoding it. */
    fun play(service: DabServiceInfo)

    /** Stops decoding audio but keeps the tuner open. */
    fun stopPlayback()

    interface Listener {
        fun onTunerStateChanged(state: TunerState)
        fun onScanProgress(progress: ScanProgress)
        /**
         * A service found by a scan, or new details of a known one (same [DabServiceInfo.key]),
         * such as announcement support signalled after the ensemble was collected.
         */
        fun onServiceFound(service: DabServiceInfo)
        fun onScanFinished(services: List<DabServiceInfo>)
        fun onAudioFormatChanged(format: PcmFormat)

        /** Interleaved 16-bit little-endian PCM in [buffer] from 0 until [length]. */
        fun onPcm(buffer: ByteArray, length: Int)
        fun onMetadata(metadata: ServiceMetadata)
        fun onSignal(signal: SignalInfo)

        /** Announcement switching in the ensemble of the playing service. */
        fun onAnnouncement(announcement: Announcement)
        fun onError(error: BackendError)
    }
}

sealed interface TunerSource {
    data class Usb(
        val device: UsbDevice,
        val connection: UsbDeviceConnection,
    ) : TunerSource {
        val fileDescriptor: Int get() = connection.fileDescriptor
    }

    /** No hardware; only meaningful for backends that can simulate a tuner. */
    data object Simulated : TunerSource
}

enum class DabBand { BAND_III, L_BAND }

enum class TunerState { CLOSED, INITIALISING, READY, SCANNING, PLAYING, FAILED }

data class ScanProgress(val channelIndex: Int, val channelCount: Int, val channelLabel: String)

data class DabServiceInfo(
    val serviceId: Long,
    val ensembleId: Int,
    val frequencyKHz: Int,
    val label: String,
    val shortLabel: String = "",
    val ensembleLabel: String = "",
    val isDabPlus: Boolean = true,
    /** International programme type code (ETSI TS 101 756 table 12), 0 if none; see [ProgrammeType]. */
    val programmeType: Int = 0,
    /** Subchannel bitrate of the audio component, 0 if unknown. */
    val bitrateKbps: Int = 0,
    /** Subchannel of the audio component within its ensemble, -1 if unknown. */
    val subchannelId: Int = -1,
    /** Announcement types the service can be interrupted by ([AnnouncementType] bits). */
    val announcementFlags: Int = 0,
    /** Announcement clusters the service belongs to. */
    val announcementClusters: List<Int> = emptyList(),
) {
    /** Stable identifier used as media ID across the UI and the media session. */
    val key: String get() = "%08X@%d".format(serviceId, frequencyKHz)

    /** Band III block label such as "11D", or the frequency in MHz for an unlisted one. */
    val channelLabel: String get() = BandIII.labelFor(frequencyKHz) ?: "%.3f MHz".format(frequencyKHz / 1000.0)
}

/**
 * Announcement switching (FIG 0/19): an announcement of the [flags] types is on air in
 * [subchannelId] for the services of [clusterId]. [active] is false, and [flags] 0, when it ends.
 */
data class Announcement(val clusterId: Int, val flags: Int, val subchannelId: Int, val active: Boolean)

/** Announcement type bits (ETSI EN 300 401 clause 8.1.6) as used by announcement flags. */
object AnnouncementType {
    const val ALARM = 1 shl 0
    const val ROAD_TRAFFIC = 1 shl 1
    const val TRANSPORT = 1 shl 2
    const val WARNING = 1 shl 3
    const val NEWS = 1 shl 4
    const val WEATHER = 1 shl 5
}

data class PcmFormat(val sampleRate: Int, val channelCount: Int)

data class ServiceMetadata(
    val serviceKey: String,
    val dynamicLabel: String? = null,
    val slideshowJpegOrPng: ByteArray? = null,
)

/** [snrDb] and [signalLevel] are backend-specific scales; [signalLevel] is 0..100 when known. */
data class SignalInfo(val locked: Boolean, val signalLevel: Int?, val snrDb: Float?) {
    /** Signal strength as 0..4 bars: 0 without lock, at least 1 with it. */
    val bars: Int
        get() = when {
            !locked -> 0
            signalLevel == null -> 4
            signalLevel >= 75 -> 4
            signalLevel >= 50 -> 3
            signalLevel >= 25 -> 2
            else -> 1
        }
}

data class BackendError(val message: String, val cause: Throwable? = null)

/** Band III channel table (ETSI EN 300 401 Annex B), shared by backends that tune by frequency. */
object BandIII {
    val channels: List<Pair<String, Int>> = listOf(
        "5A" to 174928, "5B" to 176640, "5C" to 178352, "5D" to 180064,
        "6A" to 181936, "6B" to 183648, "6C" to 185360, "6D" to 187072,
        "7A" to 188928, "7B" to 190640, "7C" to 192352, "7D" to 194064,
        "8A" to 195936, "8B" to 197648, "8C" to 199360, "8D" to 201072,
        "9A" to 202928, "9B" to 204640, "9C" to 206352, "9D" to 208064,
        "10A" to 209936, "10N" to 210096, "10B" to 211648, "10C" to 213360, "10D" to 215072,
        "11A" to 216928, "11N" to 217088, "11B" to 218640, "11C" to 220352, "11D" to 222064,
        "12A" to 223936, "12N" to 224096, "12B" to 225648, "12C" to 227360, "12D" to 229072,
        "13A" to 230784, "13B" to 232496, "13C" to 234208, "13D" to 235776, "13E" to 237488, "13F" to 239200,
    )

    fun labelFor(frequencyKHz: Int): String? = channels.firstOrNull { it.second == frequencyKHz }?.first
}
