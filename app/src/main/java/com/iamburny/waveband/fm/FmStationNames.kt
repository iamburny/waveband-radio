package com.iamburny.waveband.fm

import com.iamburny.waveband.data.FmNameDao
import com.iamburny.waveband.data.FmNameEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Remembers the RDS name heard on each FM frequency, so presets and the tuner's own preset buttons
 * can show it when the station isn't playing. AM has no RDS. A name is saved once the tuner has
 * been playing it on that frequency for [SETTLE_MS]: just after tuning, the tuner may still report
 * the previous station's name, or a blank or half-received one. A name carried over from the
 * previous frequency is not taken until it changes, as the tuner may keep it on a station without
 * RDS.
 */
@OptIn(FlowPreview::class)
class FmStationNames(private val dao: FmNameDao, radio: HeadUnitRadio, scope: CoroutineScope) {

    /** Names by [FmKey]. */
    val names: StateFlow<Map<String, String>> = dao.observe()
        .map { list -> list.associate { it.key to it.name } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        scope.launch {
            var lastKey: String? = null
            var lastName = ""
            // The previous frequency's name, ignored on this one until a different name arrives.
            var carriedOver: String? = null
            radio.state
                .map { s ->
                    val key = s.key
                    val name = s.stationName.trim()
                    if (key != lastKey) {
                        carriedOver = lastName.takeIf { lastKey != null && it.isNotEmpty() }
                        lastKey = key
                    }
                    if (name != carriedOver) carriedOver = null
                    lastName = name
                    val heard = s.active && !s.scanning && s.band == FmBand.FM && s.rds && name.isNotEmpty() && name != carriedOver
                    if (heard && key != null) key to name else null
                }
                .distinctUntilChanged()
                .debounce(SETTLE_MS)
                .filterNotNull()
                .collect { (key, name) ->
                    if (names.value[key] != name) launch(Dispatchers.IO) { dao.upsert(FmNameEntity(key, name)) }
                }
        }
    }

    private companion object {
        const val SETTLE_MS = 4_000L
    }
}
