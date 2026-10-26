package com.iamburny.waveband.data

import com.iamburny.waveband.backend.DabServiceInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Which kind of tuner found a station; stations of one are not playable on the other. */
enum class TunerOrigin { USB, DEMO }

data class Station(val info: DabServiceInfo, val favourite: Boolean) {
    val key: String get() = info.key
}

const val PRESET_COUNT = 12

/** Saved stations, favourites and presets, per [TunerOrigin]. */
class StationRepository(private val dao: StationDao) {

    fun stations(origin: TunerOrigin): Flow<List<Station>> =
        dao.observe(origin.name).map { list -> list.map { Station(it.toServiceInfo(), it.favourite) } }

    /** Keys of the [PRESET_COUNT] preset slots, null for empty ones: DAB station keys or FmKeys. */
    fun presets(origin: TunerOrigin): Flow<List<String?>> =
        dao.observePresets(origin.name).map { list ->
            val bySlot = list.associate { it.slot to it.stationKey }
            List(PRESET_COUNT) { bySlot[it] }
        }

    suspend fun count(origin: TunerOrigin): Int = dao.count(origin.name)

    suspend fun save(info: DabServiceInfo, origin: TunerOrigin) = dao.save(info, origin)

    /** After a completed scan: drops stations that were not found again, keeping favourites and presets. */
    suspend fun keepOnly(origin: TunerOrigin, keys: Collection<String>) = dao.prune(origin.name, keys.toList())

    suspend fun get(origin: TunerOrigin, key: String): Station? =
        dao.get(origin.name, key)?.let { Station(it.toServiceInfo(), it.favourite) }

    suspend fun setFavourite(origin: TunerOrigin, key: String, favourite: Boolean) = dao.setFavourite(origin.name, key, favourite)

    suspend fun setPreset(origin: TunerOrigin, slot: Int, key: String) {
        require(slot in 0 until PRESET_COUNT)
        dao.savePreset(PresetEntity(origin.name, slot, key))
    }

    suspend fun clearPreset(origin: TunerOrigin, slot: Int) = dao.clearPreset(origin.name, slot)

    suspend fun swapPresets(origin: TunerOrigin, a: Int, b: Int) {
        require(a in 0 until PRESET_COUNT && b in 0 until PRESET_COUNT)
        if (a != b) dao.swapPresets(origin.name, a, b)
    }
}
