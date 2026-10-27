package com.iamburny.waveband

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.iamburny.waveband.backend.Announcement
import com.iamburny.waveband.backend.AnnouncementType
import com.iamburny.waveband.backend.BackendError
import com.iamburny.waveband.backend.DabBackend
import com.iamburny.waveband.backend.DabServiceInfo
import com.iamburny.waveband.backend.PcmFormat
import com.iamburny.waveband.backend.ScanProgress
import com.iamburny.waveband.backend.ServiceMetadata
import com.iamburny.waveband.backend.SignalInfo
import com.iamburny.waveband.backend.StubDabBackend
import com.iamburny.waveband.backend.TunerSource
import com.iamburny.waveband.backend.TunerState
import com.iamburny.waveband.backend.omri.OmriDabBackend
import com.iamburny.waveband.data.Accent
import com.iamburny.waveband.data.AppSettings
import com.iamburny.waveband.data.DisplayMode
import com.iamburny.waveband.data.SettingsRepository
import com.iamburny.waveband.data.Source
import com.iamburny.waveband.data.Station
import com.iamburny.waveband.data.StationRepository
import com.iamburny.waveband.data.TunerOrigin
import com.iamburny.waveband.data.Visualiser
import com.iamburny.waveband.usb.UsbTunerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** Receives decoded audio; set by the playback service while it is running. */
interface PcmSink {
    fun onFormat(format: PcmFormat)
    fun write(buffer: ByteArray, length: Int)
}

/** A traffic announcement the tuner switched to; [service] carries it, the user's station stays current. */
data class AnnouncementState(val clusterId: Int, val service: DabServiceInfo)

data class ScanState(val progress: ScanProgress?, val found: Int)

/** A reception reading at [atMillis] ([SystemClock.elapsedRealtime]). */
data class SignalSample(val atMillis: Long, val signal: SignalInfo)

data class RadioState(
    val tunerName: String? = null,
    val backendName: String? = null,
    val tunerState: TunerState = TunerState.CLOSED,
    val scan: ScanState? = null,
    val current: DabServiceInfo? = null,
    val metadata: ServiceMetadata? = null,
    val signal: SignalInfo? = null,
    val audioFormat: PcmFormat? = null,
    val announcement: AnnouncementState? = null,
    val lastError: String? = null,
) {
    val tunerOpen: Boolean get() = tunerState != TunerState.CLOSED && tunerState != TunerState.FAILED
    val tunerReady: Boolean get() = tunerState == TunerState.READY || tunerState == TunerState.PLAYING

    /** The service actually tuned: the announcement's while one is on, else [current]. */
    val tuned: DabServiceInfo? get() = announcement?.service ?: current

    /** Metadata of [tuned], or null if what arrived belongs to another service. */
    val tunedMetadata: ServiceMetadata? get() = metadata?.takeIf { it.serviceKey == tuned?.key }
}

/**
 * App-wide owner of the tuner: USB permission and connection, the [DabBackend] instance, the
 * observable [RadioState], and the saved stations and presets of the active tuner origin.
 * The UI and the playback service both go through this object.
 * Public methods are expected to be called from the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RadioController(
    context: Context,
    private val repository: StationRepository,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
    private val usbBackend: DabBackend = OmriDabBackend(verboseNativeLogging = BuildConfig.DEBUG),
    private val simulatedBackend: DabBackend = StubDabBackend(),
) {

    private val usb = UsbTunerManager(context)
    // Backend calls can block, and must stay ordered (close before reopen), so they share one thread.
    private val backendThread = Executors.newSingleThreadExecutor { r -> Thread(r, "DabBackend") }
    // Database writes keep the order of the backend events that caused them (save, then prune).
    private val dbDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(RadioState())
    val state: StateFlow<RadioState> = _state.asStateFlow()

    val settings: StateFlow<AppSettings?> = settingsRepository.settings.stateIn(scope, SharingStarted.Eagerly, null)

    private val _origin = MutableStateFlow<TunerOrigin?>(null)

    /** Origin whose stations are shown: the open tuner's, or the last one used. Null until settings load. */
    val origin: StateFlow<TunerOrigin?> = _origin.asStateFlow()

    /** Saved stations of [origin], unordered. */
    val stations: StateFlow<List<Station>> = _origin
        .flatMapLatest { o -> if (o == null) flowOf(emptyList()) else repository.stations(o) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Station keys of the preset slots of [origin]. */
    val presets: StateFlow<List<String?>> = _origin
        .flatMapLatest { o -> if (o == null) flowOf(emptyList()) else repository.presets(o) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _source = MutableStateFlow<Source?>(null)

    /** What the radio plays, DAB or the head unit's FM/AM tuner. Null until settings load. */
    val source: StateFlow<Source?> = _source.asStateFlow()

    private val _alphabetical = MutableStateFlow(false)

    /** Whether the station list, and with it next/previous station, is in A–Z order. */
    val alphabetical: StateFlow<Boolean> = _alphabetical.asStateFlow()

    private val _signalHistory = MutableStateFlow<List<SignalSample>>(emptyList())

    /** Reception readings of the last [SIGNAL_HISTORY_MS] since the station was tuned, oldest first. */
    val signalHistory: StateFlow<List<SignalSample>> = _signalHistory.asStateFlow()

    private val _tunerOpened = MutableSharedFlow<TunerOrigin>(replay = 1)

    /**
     * Emits when a freshly opened tuner has become ready. The last event is replayed until
     * [consumeTunerOpened], so a UI that starts collecting a little later still sees it.
     */
    val tunerOpened: SharedFlow<TunerOrigin> = _tunerOpened.asSharedFlow()

    @Volatile
    var pcmSink: PcmSink? = null

    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null

    // The backend that owns the tuner. Switched on the main thread; each backend-thread task
    // captures the instance it was queued for.
    private var backend: DabBackend = usbBackend
    // Origin of the open tuner; read by backend callbacks to file found stations.
    @Volatile private var openOrigin: TunerOrigin? = null
    @Volatile private var awaitingReady = false
    // Incremented whenever a tuner is closed; each open gets a listener tagged with the current value.
    @Volatile private var session = 0

    // Scan bookkeeping, touched from backend callbacks.
    private val scanKeys = LinkedHashSet<String>()
    @Volatile private var scanCancelled = false
    private var resumeAfterScan: DabServiceInfo? = null

    init {
        usb.start(object : UsbTunerManager.Callback {
            override fun onPermissionResult(device: UsbDevice, granted: Boolean) {
                if (granted) openUsb(device) else setError("USB permission denied for ${device.productName ?: device.deviceName}")
            }

            override fun onDetached(device: UsbDevice) {
                if (device.deviceName == this@RadioController.device?.deviceName) disconnect()
            }
        })
        scope.launch {
            val saved = settings.first { it != null }!!
            _origin.compareAndSet(null, saved.lastOrigin)
            _source.compareAndSet(null, if (saved.headUnitTuner) saved.source else Source.DAB)
        }
    }

    /** Whether a supported tuner is plugged in, whether or not permission has been granted. */
    fun tunerAttached(): Boolean = usb.findTuners().isNotEmpty()

    /** Connects to the first attached supported tuner, asking for permission if needed. */
    fun connectFirstAvailable(): Boolean {
        val found = usb.findTuners().firstOrNull() ?: return false
        connect(found)
        return true
    }

    fun connect(device: UsbDevice) {
        if (this.device?.deviceName == device.deviceName && connection != null) return
        if (!usbBackend.supports(device)) {
            setError("${device.productName ?: device.deviceName} is not supported by ${usbBackend.name}")
            return
        }
        usb.requestPermission(device)
    }

    /** Opens the simulated backend ([StubDabBackend]) without hardware, for development. */
    fun connectSimulated() {
        disconnect()
        val b = selectBackend(simulatedBackend, TunerOrigin.DEMO)
        _state.update { it.copy(tunerName = "Demo tuner", backendName = b.name, lastError = null) }
        awaitingReady = true
        val l = SessionListener(session, TunerOrigin.DEMO)
        runOnBackend { b.open(TunerSource.Simulated, l) }
    }

    private fun openUsb(device: UsbDevice) {
        disconnect()
        val conn = usb.open(device) ?: return setError("Could not open ${device.deviceName}")
        this.device = device
        connection = conn
        val b = selectBackend(usbBackend, TunerOrigin.USB)
        _state.update { it.copy(tunerName = device.productName ?: "USB tuner", backendName = b.name, lastError = null) }
        awaitingReady = true
        val l = SessionListener(session, TunerOrigin.USB)
        runOnBackend {
            val failure = try {
                if (b.open(TunerSource.Usb(device, conn), l)) null else "${b.name} failed to open the tuner"
            } catch (t: Throwable) {
                t.message ?: "${b.name} failed to open the tuner"
            }
            if (failure != null) {
                mainHandler.post {
                    if (connection === conn) disconnect()
                    setError(failure)
                }
            }
        }
    }

    private fun selectBackend(next: DabBackend, origin: TunerOrigin): DabBackend {
        backend = next
        openOrigin = origin
        _origin.value = origin
        scope.launch { settingsRepository.setLastOrigin(origin) }
        return next
    }

    fun disconnect() {
        // Events still coming from the closing tuner belong to the old session and are dropped.
        session++
        val conn = connection
        connection = null
        device = null
        openOrigin = null
        awaitingReady = false
        resumeAfterScan = null
        val b = backend
        runOnBackend {
            b.close()
            conn?.close()
        }
        _state.update { RadioState(lastError = it.lastError) }
        _signalHistory.value = emptyList()
    }

    fun startScan() {
        if (!_state.value.tunerReady) return
        val b = backend
        // Resume afterwards only what was audibly playing, not a station the session had stopped.
        resumeAfterScan = _state.value.current?.takeIf { _state.value.tunerState == TunerState.PLAYING }
        synchronized(scanKeys) { scanKeys.clear() }
        scanCancelled = false
        _state.update { it.copy(scan = ScanState(null, 0), announcement = null, metadata = null, signal = null) }
        runOnBackend { b.startScan() }
    }

    fun cancelScan() {
        if (_state.value.scan == null) return
        scanCancelled = true
        val b = backend
        runOnBackend { b.stopScan() }
    }

    fun play(service: DabServiceInfo) {
        if (!_state.value.tunerOpen) {
            setError("Connect a tuner to play ${service.label}")
            return
        }
        resumeAfterScan = null
        // Tuning stops a running scan; its partial results must not prune the saved stations.
        if (_state.value.scan != null) scanCancelled = true
        _state.update { it.copy(current = service, metadata = null, announcement = null, audioFormat = null) }
        _signalHistory.value = emptyList()
        openOrigin?.let { o -> scope.launch { settingsRepository.setLastStation(o, service.key) } }
        val b = backend
        runOnBackend { b.play(service) }
    }

    fun stopPlayback() {
        resumeAfterScan = null
        _state.update { it.copy(announcement = null) }
        val b = backend
        runOnBackend { b.stopPlayback() }
    }

    fun findService(key: String): DabServiceInfo? =
        stations.value.firstOrNull { it.key == key }?.info ?: _state.value.current?.takeIf { it.key == key }

    /** Turning the head unit's tuner off moves listening back to DAB. */
    fun setHeadUnitTuner(enabled: Boolean) {
        if (!enabled) setSource(Source.DAB)
        scope.launch { settingsRepository.setHeadUnitTuner(enabled) }
    }

    /** FM is refused while the head unit's tuner is turned off in Settings. */
    fun setSource(source: Source) {
        if (source == Source.FM && settings.value?.headUnitTuner == false) return
        if (_source.value == source) return
        _source.value = source
        scope.launch { settingsRepository.setSource(source) }
    }

    fun setAlphabetical(enabled: Boolean) {
        _alphabetical.value = enabled
    }

    /** Stations in list order: A–Z, or grouped by ensemble. */
    fun orderedStations(): List<DabServiceInfo> {
        val infos = stations.value.map { it.info }
        return if (_alphabetical.value) StationOrder.alphabetical(infos) else StationOrder.byEnsemble(infos)
    }

    /** The saved station before (-1) or after (+1) the current one, in list order. */
    fun stationStep(direction: Int): DabServiceInfo? = StationOrder.step(orderedStations(), _state.value.current?.key, direction)

    /** The first station of the previous (-1) or next (+1) ensemble. */
    fun ensembleStep(direction: Int): DabServiceInfo? =
        StationOrder.stepEnsemble(stations.value.map { it.info }, _state.value.current?.key, direction)

    fun setFavourite(key: String, favourite: Boolean) {
        val o = _origin.value ?: return
        scope.launch(dbDispatcher) { repository.setFavourite(o, key, favourite) }
    }

    fun setPreset(slot: Int, key: String) {
        val o = _origin.value ?: return
        scope.launch(dbDispatcher) { repository.setPreset(o, slot, key) }
    }

    fun clearPreset(slot: Int) {
        val o = _origin.value ?: return
        scope.launch(dbDispatcher) { repository.clearPreset(o, slot) }
    }

    fun swapPresets(a: Int, b: Int) {
        val o = _origin.value ?: return
        scope.launch(dbDispatcher) { repository.swapPresets(o, a, b) }
    }

    fun setDisplayMode(mode: DisplayMode) {
        scope.launch { settingsRepository.setDisplayMode(mode) }
    }

    fun setAccent(accent: Accent) {
        scope.launch { settingsRepository.setAccent(accent) }
    }

    fun setSlideshow(enabled: Boolean) {
        scope.launch { settingsRepository.setSlideshow(enabled) }
    }

    fun setVisualiser(visualiser: Visualiser) {
        scope.launch { settingsRepository.setVisualiser(visualiser) }
    }

    fun setStationLogos(enabled: Boolean) {
        scope.launch { settingsRepository.setStationLogos(enabled) }
    }

    fun setTrafficAnnouncements(enabled: Boolean) {
        scope.launch { settingsRepository.setTrafficAnnouncements(enabled) }
        if (!enabled) endAnnouncement()
    }

    fun clearError() = _state.update { it.copy(lastError = null) }

    fun reportError(message: String) = setError(message)

    private fun runOnBackend(block: () -> Unit) {
        backendThread.execute {
            try {
                block()
            } catch (t: Throwable) {
                setError(t.message ?: t.toString())
            }
        }
    }

    private fun setError(message: String) = _state.update { it.copy(lastError = message) }

    private fun onTunerReady(origin: TunerOrigin) {
        scope.launch {
            if (repository.count(origin) == 0 && openOrigin == origin) startScan() else _tunerOpened.tryEmit(origin)
        }
    }

    /** Clears the [tunerOpened] event once handled, so it is not replayed to a later collector. */
    fun consumeTunerOpened() = _tunerOpened.resetReplayCache()

    suspend fun savedStation(origin: TunerOrigin, key: String): Station? = repository.get(origin, key)

    private fun onScanFinished(found: List<DabServiceInfo>) {
        val origin = openOrigin
        val keys = synchronized(scanKeys) { scanKeys.toList() + found.map { it.key } }
        val completed = !scanCancelled
        _state.update { it.copy(scan = null) }
        if (origin != null) {
            scope.launch(dbDispatcher) {
                found.forEach { repository.save(it, origin) }
                // A scan that finds nothing (antenna unplugged, no reception) keeps the old list.
                if (completed && keys.isNotEmpty()) repository.keepOnly(origin, keys.distinct())
            }
        }
        val resume = resumeAfterScan
        resumeAfterScan = null
        if (resume != null) play(resume)
    }

    /** Switches to the traffic announcement's subchannel, when the current station takes part in it. */
    private fun onAnnouncement(a: Announcement) {
        val s = _state.value
        val current = s.current ?: return
        if (!a.active) {
            if (s.announcement?.clusterId == a.clusterId) endAnnouncement()
            return
        }
        if (s.announcement != null || settings.value?.trafficAnnouncements != true) return
        if (a.flags and AnnouncementType.ROAD_TRAFFIC == 0) return
        if (current.announcementFlags and AnnouncementType.ROAD_TRAFFIC == 0 || a.clusterId !in current.announcementClusters) return
        val target = stations.value.map { it.info }.firstOrNull {
            it.frequencyKHz == current.frequencyKHz && it.ensembleId == current.ensembleId && it.subchannelId == a.subchannelId
        } ?: return
        _state.update { it.copy(announcement = AnnouncementState(a.clusterId, target)) }
        if (target.key != current.key) {
            val b = backend
            runOnBackend { b.play(target) }
        }
    }

    private fun endAnnouncement() {
        val s = _state.value
        val announcement = s.announcement ?: return
        _state.update { it.copy(announcement = null) }
        val current = s.current
        if (current != null && announcement.service.key != current.key) {
            val b = backend
            runOnBackend { b.play(current) }
        }
    }

    private inner class SessionListener(private val id: Int, private val origin: TunerOrigin) : DabBackend.Listener {
        private val current: Boolean get() = id == session

        override fun onTunerStateChanged(state: TunerState) {
            if (!current) return
            _state.update {
                it.copy(
                    tunerState = state,
                    scan = when (state) {
                        TunerState.SCANNING -> it.scan ?: ScanState(null, 0)
                        TunerState.CLOSED, TunerState.FAILED -> null
                        else -> it.scan
                    },
                    signal = if (state == TunerState.PLAYING) it.signal else null,
                )
            }
            if (state == TunerState.READY && awaitingReady) {
                awaitingReady = false
                mainHandler.post { if (current) onTunerReady(origin) }
            }
        }

        override fun onScanProgress(progress: ScanProgress) {
            if (current) _state.update { it.copy(scan = ScanState(progress, it.scan?.found ?: 0)) }
        }

        override fun onServiceFound(service: DabServiceInfo) {
            if (!current) return
            val scanning = _state.value.scan != null
            if (scanning) {
                val count = synchronized(scanKeys) {
                    scanKeys += service.key
                    scanKeys.size
                }
                _state.update { it.copy(scan = it.scan?.copy(found = count)) }
            }
            _state.update { s -> if (s.current?.key == service.key) s.copy(current = service) else s }
            scope.launch(dbDispatcher) { repository.save(service, origin) }
        }

        override fun onScanFinished(services: List<DabServiceInfo>) {
            mainHandler.post { if (current) this@RadioController.onScanFinished(services) }
        }

        override fun onAudioFormatChanged(format: PcmFormat) {
            if (!current) return
            _state.update { it.copy(audioFormat = format) }
            pcmSink?.onFormat(format)
        }

        override fun onPcm(buffer: ByteArray, length: Int) {
            if (current) pcmSink?.write(buffer, length)
        }

        override fun onMetadata(metadata: ServiceMetadata) {
            if (current) _state.update { it.copy(metadata = metadata) }
        }

        override fun onSignal(signal: SignalInfo) {
            if (!current) return
            _state.update { it.copy(signal = signal) }
            if (_state.value.tunerState != TunerState.PLAYING) return
            val now = SystemClock.elapsedRealtime()
            _signalHistory.update { history -> history.dropWhile { now - it.atMillis > SIGNAL_HISTORY_MS } + SignalSample(now, signal) }
        }

        override fun onAnnouncement(announcement: Announcement) {
            mainHandler.post { if (current) this@RadioController.onAnnouncement(announcement) }
        }

        override fun onError(error: BackendError) {
            if (current) setError(error.message)
        }
    }

    companion object {
        const val SIGNAL_HISTORY_MS = 2 * 60 * 1000L
    }
}
