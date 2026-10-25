package com.iamburny.waveband

import android.app.Application
import android.content.Context
import com.iamburny.waveband.data.SettingsRepository
import com.iamburny.waveband.data.StationDatabase
import com.iamburny.waveband.data.StationRepository
import com.iamburny.waveband.data.TunerOrigin
import com.iamburny.waveband.logos.StationLogos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class DabTunerApp : Application() {

    /** Lives as long as the process; owns the radio's flows and database writes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    val radio: RadioController by lazy {
        RadioController(this, StationRepository(StationDatabase.create(this).stations()), settingsRepository, appScope)
    }

    val stationLogos: StationLogos by lazy { StationLogos(this, appScope).also(::keepLogosUpdated) }

    /** Follows the logo setting, and fetches logos for the tuner's stations whenever they or the connection change. */
    @OptIn(FlowPreview::class)
    private fun keepLogosUpdated(logos: StationLogos) {
        val enabled = radio.settings.filterNotNull().map { it.stationLogos }.distinctUntilChanged()
        appScope.launch { enabled.collect(logos::setEnabled) }
        appScope.launch {
            combine(enabled, radio.origin, radio.stations, logos.online) { on, origin, stations, online ->
                // Demo stations have made-up IDs, so only a real tuner's stations are looked up.
                if (on && online && origin == TunerOrigin.USB) stations.map { it.info } else emptyList()
            }
                .distinctUntilChanged()
                // A scan saves stations one by one, so wait for the list to settle. Stopping is not delayed.
                .debounce { stations -> if (stations.isEmpty()) 0L else LOGO_REFRESH_DELAY_MS }
                // Switching logos off, going offline or changing tuner stops a refresh that is running.
                .collectLatest { stations -> if (stations.isNotEmpty()) logos.refresh(stations) }
        }
    }

    companion object {
        private const val LOGO_REFRESH_DELAY_MS = 5_000L

        fun from(context: Context): DabTunerApp = context.applicationContext as DabTunerApp
        fun radio(context: Context): RadioController = from(context).radio
    }
}
