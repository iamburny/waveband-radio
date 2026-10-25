package com.iamburny.waveband

import android.app.Application
import android.content.Context
import com.iamburny.waveband.data.SettingsRepository
import com.iamburny.waveband.data.StationDatabase
import com.iamburny.waveband.data.StationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DabTunerApp : Application() {

    /** Lives as long as the process; owns the radio's flows and database writes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    val radio: RadioController by lazy {
        RadioController(this, StationRepository(StationDatabase.create(this).stations()), settingsRepository, appScope)
    }

    companion object {
        fun from(context: Context): DabTunerApp = context.applicationContext as DabTunerApp
        fun radio(context: Context): RadioController = from(context).radio
    }
}
