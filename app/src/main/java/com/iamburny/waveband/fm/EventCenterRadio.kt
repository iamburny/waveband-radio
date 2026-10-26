package com.iamburny.waveband.fm

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.iamburny.waveband.playback.AudioFocus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [HeadUnitRadio] for Choiceway head units (such as the GT6-CAR), whose MCU owns the FM/AM tuner
 * and whose vendor service `com.szchoiceway.eventcenter/.EventService` relays commands to it.
 *
 * The service's binder interface is private. Only the radio calls are reached, by transaction
 * code ([Txn]), so none of its other functions (firmware upgrade, factory reset, CAN bus) can be
 * called from here. Before any command is sent, the binder must identify itself with the
 * expected descriptor and report a sane band and frequency.
 *
 * Status comes from polling the service's cached getters and from the broadcast it sends on
 * every change. The service's single radio callback slot belongs to the head unit's own radio
 * app and is never taken.
 *
 * The tuner's audio bypasses Android, so media audio focus is held for it while it has the
 * speakers, as the head unit's own radio app does: taking focus silences other players (including
 * our DAB player), and another app that takes focus for good and starts playing switches the
 * tuner off. The head unit's radio app takes focus without playing anything through Android, to
 * drive the same tuner itself, so then the tuner is left on.
 */
class EventCenterRadio(context: Context) : HeadUnitRadio {

    private val appContext = context.applicationContext
    // Binder calls block, so they run in order on one thread.
    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "HeadUnitRadio") }
    private val _state = MutableStateFlow(FmState())
    private val _problem = MutableStateFlow<String?>(null)
    override val state: StateFlow<FmState> = _state.asStateFlow()
    override val problem: StateFlow<String?> = _problem.asStateFlow()
    private val _skipRequests = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    override val skipRequests: Flow<Int> = _skipRequests.asSharedFlow()

    @Volatile private var service: IBinder? = null
    @Volatile private var bound = false
    // Raised on every connect and disconnect; work queued for an earlier connection is dropped.
    @Volatile private var generation = 0
    // Whether we put the tuner on the speakers, and still hold the mode.
    @Volatile private var ownsMode = false
    // Raised whenever the tuner's owner changes hands (activate, deactivate, losing the mode or
    // the service): a pending activation, or a focus release, from before is dropped.
    private val activation = AtomicInteger()
    // Raised by every hand-over, activate and deactivate; a watch from before stops. Unlike
    // [activation] a disconnect leaves it alone, so a watch outlives the connection.
    private val handovers = AtomicInteger()
    // Guards [service] and [ownsMode] against being changed between check and set.
    private val lock = Any()
    // Only touched on [io].
    private var polling: ScheduledFuture<*>? = null
    // Whether we sent the tuner to the speakers and haven't since sent it away. Only touched on [io],
    // so a disconnect decides after any activation already under way.
    private var modeSent = false

    private val main = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    // Only touched on the main thread.
    private val focus = AudioFocus(appContext, Looper.getMainLooper()) { lostForGood ->
        if (lostForGood) handOver()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val gen = generation
            io.execute { if (gen == generation) onConnected(binder, gen) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // The binder is dead; a check of it still under way must not mark it connected.
            generation++
            dropService("The head unit's radio service stopped")
        }

        // The service's package was replaced or removed; a fresh binding is needed.
        override fun onBindingDied(name: ComponentName) {
            disconnect()
            connect()
        }

        override fun onNullBinding(name: ComponentName) {
            dropService("The head unit's radio service refused the connection")
        }
    }

    private val infoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val band = intent.getIntExtra(EXTRA_BAND, -1)
            val frequency = intent.getIntExtra(EXTRA_FREQUENCY, -1)
            val slot = intent.getIntExtra(EXTRA_PRESET_SLOT, -1)
            val name = intent.getStringExtra(EXTRA_STATION_NAME)
            Log.d(TAG, "info: band=$band freq=$frequency slot=$slot name=$name")
            _state.update { s ->
                val b = if (FmCodec.bandOf(band) != null) band else s.bandNumber
                val f = FmCodec.bandOf(b)?.takeIf { FmCodec.isValid(it, frequency) }?.let { frequency } ?: s.frequency
                s.copy(bandNumber = b, frequency = f, presetSlot = slot, stationName = name?.trim() ?: s.stationName)
            }
        }
    }

    /**
     * Receives the service's mode changes while we hold the radio mode: another source taking
     * over (event [EVENT_MODE_CHANGE]) ends it. The steering-wheel keys also arrive here
     * ([EVENT_KEY]) rather than as Android media keys while the radio is the current source.
     */
    private val modeCallback = object : Binder() {
        init {
            attachInterface(null, CALLBACK_DESCRIPTOR)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                CALLBACK_NOTIFY -> {
                    data.enforceInterface(CALLBACK_DESCRIPTOR)
                    val event = data.readInt()
                    data.readInt()
                    val mode = data.readInt()
                    Log.d(TAG, "mode callback: event=$event mode=$mode")
                    // While we are still switching the tuner on, this reports the head unit's radio
                    // app giving it up, not someone taking it from us.
                    if (event == EVENT_MODE_CHANGE && mode != MODE_RADIO) {
                        val ticket = synchronized(lock) {
                            if (ownsMode) {
                                ownsMode = false
                                activation.incrementAndGet()
                            } else {
                                null
                            }
                        }
                        if (ticket != null) {
                            io.execute { modeSent = false }
                            releaseFocus(ticket)
                            _state.update { it.copy(active = false, ours = false) }
                        }
                    }
                    // The mode field carries the key code.
                    if (event == EVENT_KEY && ownsMode) {
                        val direction = when (mode) {
                            WHEEL_KEY_NEXT -> 1
                            WHEEL_KEY_PREVIOUS -> -1
                            else -> 0
                        }
                        if (direction != 0) {
                            if (_skipRequests.subscriptionCount.value > 0) _skipRequests.tryEmit(direction) else stepPreset(direction)
                        }
                    }
                    reply?.writeNoException()
                    return true
                }
                CALLBACK_CHECK_ACTIVE -> {
                    data.enforceInterface(CALLBACK_DESCRIPTOR)
                    reply?.writeNoException()
                    reply?.writeInt(if (ownsMode) 1 else 0)
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    override fun connect() {
        if (bound) return
        _problem.value = null
        if (!installed()) {
            _problem.value = "This head unit has no Choiceway radio service"
            return
        }
        generation++
        val intent = Intent().setComponent(ComponentName(SERVICE_PACKAGE, SERVICE_CLASS))
        bound = try {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.w(TAG, "bind refused", e)
            false
        }
        if (!bound) {
            // A failed bind still has to be released.
            runCatching { appContext.unbindService(connection) }
            _problem.value = "The head unit's radio service refused the connection"
            return
        }
        ContextCompat.registerReceiver(appContext, infoReceiver, IntentFilter(ACTION_RADIO_INFO), ContextCompat.RECEIVER_EXPORTED)
    }

    override fun disconnect() {
        if (!bound) return
        bound = false
        generation++
        // Without a connection we can't give the speakers back when another app takes focus.
        val binder = service
        if (binder != null) {
            io.execute {
                if (!modeSent) return@execute
                modeSent = false
                exitMode(binder)
            }
        }
        dropService(null)
        appContext.unregisterReceiver(infoReceiver)
        appContext.unbindService(connection)
    }

    /** Stops using the current binder at once; queued work and polling find it gone. */
    private fun dropService(problem: String?) {
        val ticket = synchronized(lock) {
            service = null
            ownsMode = false
            activation.incrementAndGet()
        }
        releaseFocus(ticket)
        _state.update { it.copy(connected = false, ours = false) }
        if (problem != null) _problem.value = problem
        // Queued after disconnect()'s switch-off, which still needs to see modeSent.
        io.execute {
            polling?.cancel(false)
            polling = null
            modeSent = false
        }
    }

    override fun refresh() = io.execute { read() }

    override fun activate(band: FmBand?, frequency: Int?) {
        require(frequency == null || (band != null && FmCodec.isValid(band, frequency))) { "$frequency needs a band it is valid in" }
        handovers.incrementAndGet()
        val gen = generation
        val ticket = activation.incrementAndGet()
        main.post {
            if (gen != generation || ticket != activation.get() || !_state.value.connected) return@post
            if (!focus.request()) {
                Log.w(TAG, "audio focus refused; the tuner stays off")
                return@post
            }
            // The head unit's radio app switches the tuner off when it loses focus; let that land
            // before switching it on.
            command(
                delayMs = FOCUS_SETTLE_MS,
                after = { if (!ownsMode) releaseFocus(ticket) },
            ) {
                if (ticket != activation.get()) return@command
                call(Txn.SET_MODE_CALLBACK) { writeInt(MODE_RADIO); writeStrongBinder(modeCallback) }
                modeSent = true
                call(Txn.SEND_MODE) { writeInt(MODE_RADIO); writeInt(0) }
                synchronized(lock) {
                    if (gen == generation && ticket == activation.get() && service != null) ownsMode = true
                }
                if (band != null && FmCodec.bandOf(callInt(Txn.GET_BAND)) != band) key(if (band == FmBand.FM) KEY_FM else KEY_AM)
                if (band != null && frequency != null) {
                    call(Txn.SEND_FREQUENCY) { writeInt(frequency); writeInt(if (band == FmBand.FM) 1 else 0) }
                }
            }
        }
    }

    override fun deactivate() {
        handovers.incrementAndGet()
        releaseFocus(activation.incrementAndGet())
        command {
            ownsMode = false
            modeSent = false
            // The service only acts on this while the radio is the current source.
            call(Txn.EXIT_MODE) { writeInt(MODE_RADIO) }
        }
    }

    /**
     * Another app took focus for good: stop claiming the tuner, and switch it off only if that app
     * starts playing through Android within [HANDOVER_WATCH_MS].
     */
    private fun handOver() {
        // A pending activation is dropped; one already under way is covered by the watch.
        synchronized(lock) {
            ownsMode = false
            activation.incrementAndGet()
        }
        val watch = handovers.incrementAndGet()
        _state.update { it.copy(active = false, ours = false) }
        val binder = service ?: return
        // If the head unit's radio app has taken the tuner, a later disconnect must leave it be.
        io.execute { modeSent = false }
        watchForPlayback(watch, binder, SystemClock.uptimeMillis() + HANDOVER_WATCH_MS)
    }

    private fun watchForPlayback(watch: Int, binder: IBinder, until: Long) {
        main.postDelayed({
            if (watch != handovers.get()) return@postDelayed
            if (audioManager.isMusicActive) {
                io.execute { if (watch == handovers.get()) exitMode(binder) }
            } else if (SystemClock.uptimeMillis() < until) {
                watchForPlayback(watch, binder, until)
            }
        }, HANDOVER_POLL_MS)
    }

    /** Sends the tuner away through [binder], which may belong to an ended connection. Runs on [io]. */
    private fun exitMode(binder: IBinder) {
        try {
            transactOn(binder, Txn.EXIT_MODE, { writeInt(MODE_RADIO) }) { }
        } catch (e: RemoteException) {
            Log.w(TAG, "switching the tuner off failed", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "switching the tuner off failed", e)
        }
    }

    /** Gives up focus unless a newer owner change than [ticket] has happened since. */
    private fun releaseFocus(ticket: Int) {
        main.post { if (ticket == activation.get()) focus.abandon() }
    }

    override fun selectBand(band: FmBand) = command { key(if (band == FmBand.FM) KEY_FM else KEY_AM) }

    override fun seek(direction: Int) = command { key(if (direction > 0) KEY_SEEK_UP else KEY_SEEK_DOWN) }

    override fun step(direction: Int) = command { key(if (direction > 0) KEY_STEP_UP else KEY_STEP_DOWN) }

    override fun tune(band: FmBand, frequency: Int) = command {
        require(FmCodec.isValid(band, frequency)) { "$frequency is not a valid $band frequency" }
        if (_state.value.band != band) key(if (band == FmBand.FM) KEY_FM else KEY_AM)
        call(Txn.SEND_FREQUENCY) { writeInt(frequency); writeInt(if (band == FmBand.FM) 1 else 0) }
    }

    override fun toggleLocal() = command { key(KEY_LOCAL_DX) }

    override fun toggleMono() = command { key(KEY_STEREO_MONO) }

    override fun setRdsEnabled(enabled: Boolean) = command {
        call(Txn.CHANGE_SETUP) { writeString(SETTING_RDS); writeString(if (enabled) "1" else "0") }
    }

    override fun recallPreset(slot: Int) = command {
        require(slot in 0 until FmCodec.PRESETS_PER_BAND)
        key(KEY_PRESET_1 + slot)
    }

    override fun storePreset(slot: Int) = command {
        require(slot in 0 until FmCodec.PRESETS_PER_BAND)
        key(KEY_STORE_PRESET_1 + slot)
    }

    private fun installed(): Boolean = isInstalled(appContext)

    private fun onConnected(binder: IBinder, gen: Int) {
        // A restarted service connects again without a disconnect() in between.
        polling?.cancel(false)
        polling = null
        val problem = try {
            when {
                binder.interfaceDescriptor != SERVICE_DESCRIPTOR -> "Unexpected radio service (${binder.interfaceDescriptor})"
                else -> {
                    service = binder
                    val band = callInt(Txn.GET_BAND)
                    val frequency = callInt(Txn.GET_FREQUENCY)
                    val kind = FmCodec.bandOf(band)
                    when {
                        kind == null -> "The radio service reported band $band"
                        !FmCodec.isValid(kind, frequency) -> "The radio service reported frequency $frequency on $kind"
                        else -> null
                    }
                }
            }
        } catch (e: RemoteException) {
            "The radio service failed: ${e.message}"
        } catch (e: RuntimeException) {
            "The radio service answered unexpectedly: ${e.message}"
        }
        // disconnect() ran while this was being checked.
        if (gen != generation) {
            service = null
            return
        }
        if (problem != null) {
            Log.w(TAG, problem)
            service = null
            _problem.value = problem
            return
        }
        _problem.value = null
        _state.update { it.copy(connected = true) }
        read(withSettings = true)
        polling = io.scheduleWithFixedDelay({ read() }, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Reads the service's cached tuner state, and [withSettings] the settings that only change
     * when someone changes them. Runs on [io].
     */
    private fun read(withSettings: Boolean = false) {
        if (service == null) return
        try {
            val band = callInt(Txn.GET_BAND)
            val frequency = callInt(Txn.GET_FREQUENCY)
            val mode = callInt(Txn.GET_VALID_MODE)
            val presets = callIntArray(Txn.GET_PRESETS)
            val slot = callInt(Txn.GET_PRESET_SLOT)
            val name = callString(Txn.GET_STATION_NAME).orEmpty().trim()
            val stereo = callBoolean(Txn.GET_STEREO_ICON)
            val tp = callBoolean(Txn.GET_TP_ICON)
            val ta = callBoolean(Txn.GET_TA)
            val rds = callBoolean(Txn.GET_RDS)
            val af = callBoolean(Txn.GET_AF)
            val local = callBoolean(Txn.GET_LOCAL)
            // Calls other firmware may lack: one failing leaves that value unknown, not the rest.
            val mono = optional { !callBoolean(Txn.GET_STEREO_MODE) }
            // RDS is on until someone turns it off.
            val rdsEnabled = if (withSettings) optional { setting(SETTING_RDS) != "0" } else _state.value.rdsEnabled
            val region = if (withSettings) optional { setting(SETTING_REGION)?.toIntOrNull()?.takeIf { it in 0..4 } } else _state.value.region
            val scanning = callBoolean(Txn.GET_SCANNING)
            if (service == null) return
            _state.update {
                it.copy(
                    active = mode == MODE_RADIO,
                    ours = ownsMode,
                    bandNumber = band,
                    frequency = frequency,
                    presetSlot = slot,
                    stationName = name,
                    stereo = stereo,
                    trafficProgramme = tp,
                    trafficAnnouncements = ta,
                    rds = rds,
                    alternativeFrequencies = af,
                    local = local,
                    mono = mono ?: it.mono,
                    rdsEnabled = rdsEnabled,
                    region = region,
                    scanning = scanning,
                    presets = presets?.toList() ?: it.presets,
                )
            }
        } catch (e: RemoteException) {
            Log.w(TAG, "read failed", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "read failed", e)
        }
    }

    /**
     * Runs [block] on [io], after [delayMs], once the service is connected and checked, then
     * re-reads the state. [after] runs on [io] whether or not [block] ran or succeeded.
     */
    private fun command(delayMs: Long = 0, after: () -> Unit = {}, block: () -> Unit) {
        val gen = generation
        io.schedule(Runnable {
            try {
                if (gen != generation || service == null || !_state.value.connected) {
                    Log.w(TAG, "radio command ignored: not connected")
                    return@Runnable
                }
                try {
                    block()
                } catch (e: RemoteException) {
                    Log.w(TAG, "radio command failed", e)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "radio command failed", e)
                }
                read(withSettings = true)
            } finally {
                after()
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun key(code: Int) = call(Txn.SEND_KEY) { writeInt(code) }

    private fun <T> transact(txn: Int, write: Parcel.() -> Unit, read: Parcel.() -> T): T =
        transactOn(service ?: throw RemoteException("not connected"), txn, write, read)

    private fun <T> transactOn(binder: IBinder, txn: Int, write: Parcel.() -> Unit, read: Parcel.() -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            data.write()
            if (!binder.transact(txn, data, reply, 0)) throw RemoteException("transaction $txn not handled")
            reply.readException()
            return reply.read()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun call(txn: Int, write: Parcel.() -> Unit = {}) = transact(txn, write) { }
    private fun callInt(txn: Int): Int = transact(txn, {}) { readInt() }
    private fun callBoolean(txn: Int): Boolean = transact(txn, {}) { readInt() != 0 }
    private fun callString(txn: Int): String? = transact(txn, {}) { readString() }
    private fun callIntArray(txn: Int): IntArray? = transact(txn, {}) { createIntArray() }

    private fun <T> optional(read: () -> T): T? = try {
        read()
    } catch (e: RemoteException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    /** A setting the vendor service keeps, or null if it has none under [key]. */
    private fun setting(key: String): String? =
        transact(Txn.GET_SETTING_STRING, { writeString(key); writeString("") }) { readString() }?.ifEmpty { null }

    /** Transaction codes of the radio calls on the service interface. */
    private object Txn {
        const val SEND_MODE = 1
        const val SEND_KEY = 2
        const val SEND_FREQUENCY = 6
        const val GET_FREQUENCY = 12
        const val GET_PRESETS = 13
        const val GET_BAND = 14
        const val GET_PRESET_SLOT = 15
        const val GET_RDS = 16
        // Despite its name on the service, this returns the RDS programme service name.
        const val GET_STATION_NAME = 19
        const val GET_AF = 20
        const val GET_TA = 21
        // True while FM is set to stereo, false for mono.
        const val GET_STEREO_MODE = 22
        const val GET_LOCAL = 23
        const val GET_SCANNING = 24
        const val GET_STEREO_ICON = 26
        const val GET_TP_ICON = 27
        const val SET_MODE_CALLBACK = 30
        // Reads the service's own settings store.
        const val GET_SETTING_STRING = 44
        const val EXIT_MODE = 31
        const val GET_VALID_MODE = 46
        // Saves a setting and applies it, such as sending it on to the tuner.
        const val CHANGE_SETUP = 78
    }

    companion object {
        /** Whether this device has the vendor service at all. */
        fun isInstalled(context: Context): Boolean = try {
            context.packageManager.getPackageInfo(SERVICE_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

        private const val TAG = "HeadUnitRadio"
        private const val SERVICE_PACKAGE = "com.szchoiceway.eventcenter"
        private const val SERVICE_CLASS = "com.szchoiceway.eventcenter.EventService"
        private const val SERVICE_DESCRIPTOR = "com.szchoiceway.eventcenter.IEventService"
        private const val CALLBACK_DESCRIPTOR = "com.szchoiceway.eventcenter.ICallbackfn"
        private const val CALLBACK_NOTIFY = 1
        private const val CALLBACK_CHECK_ACTIVE = 2
        private const val EVENT_MODE_CHANGE = 4097
        private const val EVENT_KEY = 4098

        // Steering-wheel keys as [EVENT_KEY] reports them.
        private const val WHEEL_KEY_NEXT = 2
        private const val WHEEL_KEY_PREVIOUS = 3

        private const val ACTION_RADIO_INFO = "com.choiceway.eventcenter.EventUtils.ZXW_RADIO_INFO_EVT"
        private const val EXTRA_BAND = "RadioBndNum"
        private const val EXTRA_PRESET_SLOT = "RadioTuneNum"
        private const val EXTRA_FREQUENCY = "RadioCurFreq"
        private const val EXTRA_STATION_NAME = "RadioRDSInfor"

        // Keys in the service's settings store.
        private const val SETTING_RDS = "SYS_RDS_OnOff"
        private const val SETTING_REGION = "COM.SZCHOICEWAY_RADIO_ZONE_SETTINGS"

        /** The service's source number for the FM/AM tuner. */
        private const val MODE_RADIO = 1

        // Tuner keys. Presets 1-6 are consecutive, as are the "store preset" keys.
        private const val KEY_PRESET_1 = 1
        private const val KEY_STORE_PRESET_1 = 7
        private const val KEY_STEP_DOWN = 14
        private const val KEY_STEP_UP = 15
        private const val KEY_SEEK_DOWN = 16
        private const val KEY_SEEK_UP = 17
        private const val KEY_STEREO_MONO = 19
        private const val KEY_LOCAL_DX = 20
        private const val KEY_FM = 30
        private const val KEY_AM = 31

        private const val POLL_MS = 1000L
        private const val FOCUS_SETTLE_MS = 300L
        private const val HANDOVER_WATCH_MS = 5000L
        private const val HANDOVER_POLL_MS = 250L
    }
}
