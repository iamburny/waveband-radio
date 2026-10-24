package com.matt.dabtuner

import android.app.Application
import android.content.Context
import com.matt.dabtuner.data.SettingsRepository
import com.matt.dabtuner.data.StationDatabase
import com.matt.dabtuner.data.StationRepository
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
