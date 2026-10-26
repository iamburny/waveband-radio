package com.iamburny.waveband

import android.app.Application
import android.content.Context
import com.iamburny.waveband.data.SettingsRepository
import com.iamburny.waveband.data.StationDatabase
import com.iamburny.waveband.data.StationRepository
import com.iamburny.waveband.data.TunerOrigin
import com.iamburny.waveband.fm.EventCenterRadio
import com.iamburny.waveband.fm.FakeHeadUnitRadio
import com.iamburny.waveband.fm.FmStationNames
import com.iamburny.waveband.fm.HeadUnitRadio
import com.iamburny.waveband.logos.StationLogos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class DabTunerApp : Application() {

    /** Lives as long as the process; owns the radio's flows and database writes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    private val database: StationDatabase by lazy { StationDatabase.create(this) }

    val radio: RadioController by lazy {
        RadioController(this, StationRepository(database.stations()), settingsRepository, appScope)
    }

    /**
     * The head unit's own FM/AM tuner, connected for the life of the process; it reports itself
     * connected only on head units with a supported one. Debug builds simulate one elsewhere.
     * First used on the main thread.
     */
    val headUnitRadio: HeadUnitRadio by lazy {
        val radio = if (BuildConfig.DEBUG && !EventCenterRadio.isInstalled(this)) FakeHeadUnitRadio(this) else EventCenterRadio(this)
        radio.also { it.connect() }
    }

    /** RDS names heard on FM/AM frequencies, saved while the head unit's tuner plays. */
    val fmStationNames: FmStationNames by lazy { FmStationNames(database.fmNames(), headUnitRadio, appScope) }

    val stationLogos: StationLogos by lazy { StationLogos(this, appScope).also(::keepLogosUpdated) }

    /** Follows the logo setting, and fetches logos for the tuner's stations whenever they or the connection change. */
    @OptIn(FlowPreview::class)
    private fun keepLogosUpdated(logos: StationLogos) {
        val enabled = radio.settings.filterNotNull().map { it.stationLogos }.distinctUntilChanged()
        appScope.launch { enabled.collect(logos::setEnabled) }
        appScope.launch {
            // Ticks so that lookups which failed are retried while the app keeps running.
            val ticks = flow {
                var tick = 0
                while (true) {
                    emit(tick++)
                    delay(LOGO_RETRY_INTERVAL_MS)
                }
            }
            combine(enabled, radio.origin, radio.stations, logos.online, ticks) { on, origin, stations, online, tick ->
                // Demo stations have made-up IDs, so only a real tuner's stations are looked up.
                if (on && online && origin == TunerOrigin.USB) stations.map { it.info } to tick else null
            }
                .distinctUntilChanged()
                // A scan saves stations one by one, so wait for the list to settle. Stopping is not delayed.
                .debounce { request -> if (request == null) 0L else LOGO_REFRESH_DELAY_MS }
                // Switching logos off, going offline or changing tuner stops a refresh that is running.
                .collectLatest { request -> if (request != null && request.first.isNotEmpty()) logos.refresh(request.first) }
        }
    }

    companion object {
        private const val LOGO_REFRESH_DELAY_MS = 5_000L
        private const val LOGO_RETRY_INTERVAL_MS = 60L * 60 * 1000

        fun from(context: Context): DabTunerApp = context.applicationContext as DabTunerApp
        fun radio(context: Context): RadioController = from(context).radio
    }
}
