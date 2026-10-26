package com.iamburny.waveband.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class DisplayMode { AUTO, DAY, NIGHT }

enum class Accent { AMBER, TEAL, BLUE, CORAL }

enum class Region(val label: String) { UK("United Kingdom · Band III") }

/** What the radio plays: a DAB station from the USB tuner, or the head unit's own FM/AM tuner. */
enum class Source { DAB, FM }

data class AppSettings(
    val displayMode: DisplayMode = DisplayMode.AUTO,
    val accent: Accent = Accent.AMBER,
    val trafficAnnouncements: Boolean = true,
    val slideshow: Boolean = true,
    /** Download station logos from broadcasters through RadioDNS. */
    val stationLogos: Boolean = true,
    val region: Region = Region.UK,
    val lastStationKey: String? = null,
    val lastOrigin: TunerOrigin = TunerOrigin.USB,
    val source: Source = Source.DAB,
    /** Offer the head unit's own FM/AM tuner, where there is one. */
    val headUnitTuner: Boolean = true,
)

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {

    private val store = context.applicationContext.settingsStore

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            displayMode = p[DISPLAY_MODE].toEnum(DisplayMode.AUTO),
            accent = p[ACCENT].toEnum(Accent.AMBER),
            trafficAnnouncements = p[TRAFFIC] ?: true,
            slideshow = p[SLIDESHOW] ?: true,
            stationLogos = p[STATION_LOGOS] ?: true,
            region = p[REGION].toEnum(Region.UK),
            lastStationKey = p[LAST_STATION],
            lastOrigin = p[LAST_ORIGIN].toEnum(TunerOrigin.USB),
            source = p[SOURCE].toEnum(Source.DAB),
            headUnitTuner = p[HEAD_UNIT_TUNER] ?: true,
        )
    }

    suspend fun setDisplayMode(mode: DisplayMode) = store.edit { it[DISPLAY_MODE] = mode.name }

    suspend fun setAccent(accent: Accent) = store.edit { it[ACCENT] = accent.name }

    suspend fun setTrafficAnnouncements(enabled: Boolean) = store.edit { it[TRAFFIC] = enabled }

    suspend fun setSlideshow(enabled: Boolean) = store.edit { it[SLIDESHOW] = enabled }

    suspend fun setStationLogos(enabled: Boolean) = store.edit { it[STATION_LOGOS] = enabled }

    suspend fun setLastStation(origin: TunerOrigin, key: String) = store.edit {
        it[LAST_ORIGIN] = origin.name
        it[LAST_STATION] = key
    }

    suspend fun setLastOrigin(origin: TunerOrigin) = store.edit { it[LAST_ORIGIN] = origin.name }

    suspend fun setSource(source: Source) = store.edit { it[SOURCE] = source.name }

    suspend fun setHeadUnitTuner(enabled: Boolean) = store.edit { it[HEAD_UNIT_TUNER] = enabled }

    private companion object {
        val DISPLAY_MODE = stringPreferencesKey("display_mode")
        val ACCENT = stringPreferencesKey("accent")
        val TRAFFIC = booleanPreferencesKey("traffic_announcements")
        val SLIDESHOW = booleanPreferencesKey("slideshow")
        val STATION_LOGOS = booleanPreferencesKey("station_logos")
        val REGION = stringPreferencesKey("region")
        val LAST_STATION = stringPreferencesKey("last_station")
        val LAST_ORIGIN = stringPreferencesKey("last_origin")
        val SOURCE = stringPreferencesKey("source")
        val HEAD_UNIT_TUNER = booleanPreferencesKey("head_unit_tuner")

        inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
            this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
    }
}
