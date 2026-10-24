package com.matt.dabtuner.backend.omri

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.util.Log
import com.matt.dabtuner.backend.Announcement
import com.matt.dabtuner.backend.BackendError
import com.matt.dabtuner.backend.BandIII
import com.matt.dabtuner.backend.DabBackend
import com.matt.dabtuner.backend.DabBand
import com.matt.dabtuner.backend.DabServiceInfo
import com.matt.dabtuner.backend.ScanProgress
import com.matt.dabtuner.backend.ServiceMetadata
import com.matt.dabtuner.backend.SignalInfo
import com.matt.dabtuner.backend.TunerSource
import com.matt.dabtuner.backend.TunerState
import com.matt.dabtuner.usb.SupportedTuners
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Backend for the Raontech-based 16C0:05DC stick, using IRT's omri-usb DAB stack (libomri.so).
 * The stick delivers decoded FIC/MSC bytes; omri-usb parses the ensemble and extracts audio frames,
 * which are decoded here: DAB+ through MediaCodec, classic DAB (MP2) through libmpg123.
 *
 * Methods are called on the RadioController's backend thread; native callbacks arrive on
 * omri-usb worker threads.
 */
class OmriDabBackend(private val verboseNativeLogging: Boolean = false) : DabBackend {

    override val name: String = "omri-usb"

    @Volatile private var listener: DabBackend.Listener? = null
    @Volatile private var handle = 0L
    @Volatile private var scanning = false
    @Volatile private var playing: DabServiceInfo? = null
    // Set once the tuner confirms [playing] has started; audio and labels are accepted only then.
    @Volatile private var started = false
    // A failed tuner stays failed until it is closed and reopened.
    @Volatile private var failed = false
    @Volatile private var pipeline: AudioPipeline? = null
    @Volatile private var lastLabel: String? = null
    @Volatile private var lastSlide: ByteArray? = null
    private val found = LinkedHashMap<String, DabServiceInfo>()
    // FIG 0/18 support by (ensemble ID, service ID); it can arrive before or after the service itself.
    private val announcementSupport = HashMap<Pair<Int, Long>, Pair<Int, List<Int>>>()
    private var claimedInterface: UsbInterface? = null
    private var usb: TunerSource.Usb? = null

    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "OmriDabBackendTimer").apply { isDaemon = true } }
    @Volatile private var startTimeout: ScheduledFuture<*>? = null

    init {
        // Loading early surfaces a broken native build in logcat even when no stick is attached.
        if (OmriNative.load()) OmriNative.nativeSetLogging(verboseNativeLogging)
    }

    override fun supports(device: UsbDevice): Boolean = SupportedTuners.isSupported(device)

    override fun open(source: TunerSource, listener: DabBackend.Listener): Boolean {
        if (source !is TunerSource.Usb) return false
        if (!OmriNative.load()) throw IOException("Native DAB library is missing from this build")

        val device = source.device
        if (device.interfaceCount == 0) throw IOException("${device.deviceName} has no USB interface")
        val usbInterface = device.getInterface(0)
        if (!source.connection.claimInterface(usbInterface, true)) {
            throw IOException("Could not claim the tuner's USB interface")
        }

        this.listener = listener
        listener.onTunerStateChanged(TunerState.INITIALISING)
        val info = arrayOfNulls<String>(1)
        val h = OmriNative.nativeOpen(source.fileDescriptor, device.deviceName, callbacks, info)
        Log.i(TAG, "Tuner probe on ${device.deviceName}: ${info[0]}")
        if (h == 0L) {
            source.connection.releaseInterface(usbInterface)
            this.listener = null
            throw IOException("No DAB tuner answered on ${device.productName ?: device.deviceName} (${info[0]})")
        }
        usb = source
        claimedInterface = usbInterface
        failed = false
        pipeline = AudioPipeline(listener)
        handle = h
        return true
    }

    override fun close() {
        val l = listener
        cancelStartTimeout()
        scanning = false
        playing = null
        started = false
        val h = handle
        handle = 0L
        if (h != 0L) OmriNative.nativeClose(h)
        pipeline?.release()
        pipeline = null
        claimedInterface?.let { iface -> usb?.connection?.releaseInterface(iface) }
        claimedInterface = null
        usb = null
        listener = null
        l?.onTunerStateChanged(TunerState.CLOSED)
    }

    override fun startScan(band: DabBand) {
        val h = handle
        val l = listener ?: return
        if (h == 0L || failed) return
        if (band != DabBand.BAND_III) {
            l.onError(BackendError("Only Band III is supported by this tuner"))
            return
        }
        stopPlayback()
        synchronized(found) {
            found.clear()
            announcementSupport.clear()
        }
        scanning = true
        l.onTunerStateChanged(TunerState.SCANNING)
        OmriNative.nativeStartScan(h)
    }

    override fun stopScan() {
        val h = handle
        if (h != 0L && scanning) OmriNative.nativeStopScan(h)
    }

    override fun play(service: DabServiceInfo) {
        val h = handle
        if (h == 0L || listener == null || failed) return
        if (scanning) stopScan()
        started = false
        pipeline?.flush()
        lastLabel = null
        lastSlide = null
        playing = service
        OmriNative.nativeStartService(h, service.frequencyKHz, service.ensembleId, 0, service.serviceId)
        cancelStartTimeout()
        startTimeout = timer.schedule({
            if (playing === service) {
                listener?.onError(BackendError("${service.label} was not found on ${"%.3f".format(service.frequencyKHz / 1000.0)} MHz"))
            }
        }, START_TIMEOUT_S, TimeUnit.SECONDS)
    }

    override fun stopPlayback() {
        cancelStartTimeout()
        if (playing == null) return
        playing = null
        started = false
        pipeline?.flush()
        val h = handle
        if (h != 0L) OmriNative.nativeStopService(h)
        listener?.onTunerStateChanged(TunerState.READY)
    }

    private fun cancelStartTimeout() {
        startTimeout?.cancel(false)
        startTimeout = null
    }

    private val callbacks = object : OmriNative.Callbacks {
        override fun onState(state: Int) {
            val l = listener ?: return
            if (failed) return
            when (state) {
                OmriNative.STATE_READY -> {
                    if (scanning) {
                        scanning = false
                        l.onScanFinished(synchronized(found) { found.values.toList() })
                    }
                    l.onTunerStateChanged(if (playing != null && started) TunerState.PLAYING else TunerState.READY)
                }
                OmriNative.STATE_SCANNING -> l.onTunerStateChanged(TunerState.SCANNING)
                OmriNative.STATE_FAILED -> {
                    failed = true
                    scanning = false
                    l.onTunerStateChanged(TunerState.FAILED)
                    l.onError(BackendError("The tuner stopped responding"))
                }
            }
        }

        override fun onScanProgress(channelIndex: Int, channelCount: Int) {
            val l = listener ?: return
            if (!scanning || channelIndex >= channelCount) return
            val label = BandIII.channels.getOrNull(channelIndex)?.first.orEmpty()
            l.onScanProgress(ScanProgress(channelIndex, channelCount, label))
        }

        override fun onServiceFound(
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
        ) {
            val l = listener ?: return
            if (!isProgrammeService || (audioCodec != OmriNative.CODEC_AAC && audioCodec != OmriNative.CODEC_MP2)) return
            var service = DabServiceInfo(
                serviceId = serviceId,
                ensembleId = ensembleId,
                frequencyKHz = frequencyKHz,
                label = label.decodeToString().trim(),
                shortLabel = shortLabel.decodeToString().trim(),
                ensembleLabel = ensembleLabel.decodeToString().trim(),
                isDabPlus = audioCodec == OmriNative.CODEC_AAC,
                programmeType = programmeType,
                bitrateKbps = bitrateKbps,
                subchannelId = subchannelId,
                announcementFlags = announcementFlags,
                announcementClusters = clusterIds.map { it.toInt() and 0xFF },
            )
            synchronized(found) {
                announcementSupport[ensembleId to serviceId]?.let { (flags, clusters) ->
                    service = service.copy(announcementFlags = flags, announcementClusters = clusters)
                }
                found[service.key] = service
            }
            l.onServiceFound(service)
        }

        override fun onAnnouncementSupport(ensembleId: Int, serviceId: Long, flags: Int, clusterIds: ByteArray) {
            val l = listener ?: return
            val clusters = clusterIds.map { it.toInt() and 0xFF }
            val updated = synchronized(found) {
                announcementSupport[ensembleId to serviceId] = flags to clusters
                val known = found.values.filter { it.ensembleId == ensembleId && it.serviceId == serviceId } +
                    listOfNotNull(playing?.takeIf { it.ensembleId == ensembleId && it.serviceId == serviceId })
                known.distinctBy { it.key }.map { it.copy(announcementFlags = flags, announcementClusters = clusters) }
                    .onEach { if (found.containsKey(it.key)) found[it.key] = it }
            }
            updated.forEach(l::onServiceFound)
        }

        override fun onAnnouncement(clusterId: Int, flags: Int, subchannelId: Int, active: Boolean) {
            // Ends pass even while a switch is pending, so a target that never starts cannot strand the listener.
            if (playing == null || (active && !started)) return
            listener?.onAnnouncement(Announcement(clusterId, flags, subchannelId, active))
        }

        override fun onServiceStarted(serviceId: Long) {
            val current = playing ?: return
            if (current.serviceId != serviceId) return
            cancelStartTimeout()
            started = true
            listener?.onTunerStateChanged(TunerState.PLAYING)
        }

        override fun onServiceStopped(serviceId: Long) = Unit

        override fun onReception(locked: Boolean, antennaLevel: Int) {
            listener?.onSignal(SignalInfo(locked, (antennaLevel.coerceIn(0, 6) * 100) / 6, null))
        }

        override fun onAudio(frame: ByteArray, codec: Int, channels: Int, sampleRate: Int, sbr: Boolean, ps: Boolean) {
            if (playing == null || !started) return
            pipeline?.submit(frame, codec, channels, sampleRate, sbr, ps)
        }

        override fun onDynamicLabel(utf8: ByteArray) {
            val current = playing ?: return
            if (!started) return
            val text = utf8.decodeToString().trim()
            lastLabel = text
            listener?.onMetadata(ServiceMetadata(current.key, text, lastSlide))
        }

        override fun onSlideshow(image: ByteArray, contentSubType: Int) {
            val current = playing ?: return
            if (!started) return
            lastSlide = image
            listener?.onMetadata(ServiceMetadata(current.key, lastLabel, image))
        }
    }

    private companion object {
        const val TAG = "OmriDabBackend"
        const val START_TIMEOUT_S = 20L
    }
}
