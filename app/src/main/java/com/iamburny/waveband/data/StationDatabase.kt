package com.iamburny.waveband.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import com.iamburny.waveband.backend.DabServiceInfo
import kotlinx.coroutines.flow.Flow

/** A station found by a scan. [origin] is the [TunerOrigin] name of the tuner that found it. */
@Entity(tableName = "stations", primaryKeys = ["origin", "key"])
data class StationEntity(
    val key: String,
    val origin: String,
    val serviceId: Long,
    val ensembleId: Int,
    val frequencyKHz: Int,
    val label: String,
    @ColumnInfo(defaultValue = "0") val ensembleEcc: Int,
    val shortLabel: String,
    val ensembleLabel: String,
    val isDabPlus: Boolean,
    val programmeType: Int,
    val bitrateKbps: Int,
    val subchannelId: Int,
    val announcementFlags: Int,
    /** Comma-separated cluster IDs. */
    val announcementClusters: String,
    val favourite: Boolean,
) {
    fun toServiceInfo() = DabServiceInfo(
        serviceId = serviceId,
        ensembleId = ensembleId,
        frequencyKHz = frequencyKHz,
        label = label,
        ensembleEcc = ensembleEcc,
        shortLabel = shortLabel,
        ensembleLabel = ensembleLabel,
        isDabPlus = isDabPlus,
        programmeType = programmeType,
        bitrateKbps = bitrateKbps,
        subchannelId = subchannelId,
        announcementFlags = announcementFlags,
        announcementClusters = announcementClusters.split(',').mapNotNull { it.toIntOrNull() },
    )

    companion object {
        fun from(info: DabServiceInfo, origin: TunerOrigin, favourite: Boolean) = StationEntity(
            key = info.key,
            origin = origin.name,
            serviceId = info.serviceId,
            ensembleId = info.ensembleId,
            frequencyKHz = info.frequencyKHz,
            label = info.label,
            ensembleEcc = info.ensembleEcc,
            shortLabel = info.shortLabel,
            ensembleLabel = info.ensembleLabel,
            isDabPlus = info.isDabPlus,
            programmeType = info.programmeType,
            bitrateKbps = info.bitrateKbps,
            subchannelId = info.subchannelId,
            announcementFlags = info.announcementFlags,
            announcementClusters = info.announcementClusters.joinToString(","),
            favourite = favourite,
        )
    }
}

/** Preset [slot] (0-based) of the given origin, pointing at a station key. */
@Entity(tableName = "presets", primaryKeys = ["origin", "slot"])
data class PresetEntity(val origin: String, val slot: Int, val stationKey: String)

@Dao
interface StationDao {

    @Query("SELECT * FROM stations WHERE origin = :origin")
    fun observe(origin: String): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE origin = :origin AND `key` = :key")
    suspend fun get(origin: String, key: String): StationEntity?

    @Query("SELECT COUNT(*) FROM stations WHERE origin = :origin")
    suspend fun count(origin: String): Int

    @Upsert
    suspend fun upsert(station: StationEntity)

    @Query("UPDATE stations SET favourite = :favourite WHERE origin = :origin AND `key` = :key")
    suspend fun setFavourite(origin: String, key: String, favourite: Boolean)

    /** Saves [info] and keeps the favourite flag of an existing entry. */
    @Transaction
    suspend fun save(info: DabServiceInfo, origin: TunerOrigin) {
        upsert(StationEntity.from(info, origin, favourite = get(origin.name, info.key)?.favourite ?: false))
    }

    /** Removes stations of [origin] that are not in [keep], unless they are favourites or presets. */
    @Query(
        "DELETE FROM stations WHERE origin = :origin AND favourite = 0 AND `key` NOT IN (:keep) " +
            "AND `key` NOT IN (SELECT stationKey FROM presets WHERE origin = :origin)"
    )
    suspend fun prune(origin: String, keep: List<String>)

    @Query("SELECT * FROM presets WHERE origin = :origin")
    fun observePresets(origin: String): Flow<List<PresetEntity>>

    @Upsert
    suspend fun setPreset(preset: PresetEntity)

    @Query("DELETE FROM presets WHERE origin = :origin AND slot = :slot")
    suspend fun clearPreset(origin: String, slot: Int)
}

@Database(
    entities = [StationEntity::class, PresetEntity::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class StationDatabase : RoomDatabase() {
    abstract fun stations(): StationDao

    companion object {
        fun create(context: Context): StationDatabase =
            Room.databaseBuilder(context, StationDatabase::class.java, "stations.db").build()
    }
}
