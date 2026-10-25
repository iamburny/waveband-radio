package com.iamburny.waveband

import com.iamburny.waveband.backend.DabServiceInfo

/** Station list orderings and the next/previous station and ensemble lookups built on them. */
object StationOrder {

    /** Grouped by ensemble (by name, then frequency), then by station name. */
    fun byEnsemble(services: List<DabServiceInfo>): List<DabServiceInfo> =
        services.sortedWith(
            compareBy<DabServiceInfo>({ it.ensembleLabel.lowercase() }, { it.frequencyKHz }, { it.ensembleId }, { it.label.lowercase() }, { it.key })
        )

    fun alphabetical(services: List<DabServiceInfo>): List<DabServiceInfo> =
        services.sortedWith(compareBy<DabServiceInfo>({ it.label.lowercase() }, { it.key }))

    /**
     * The station [direction] (+1 or -1) steps from [currentKey] in [ordered], wrapping around.
     * Without a current station, +1 gives the first and -1 the last.
     */
    fun step(ordered: List<DabServiceInfo>, currentKey: String?, direction: Int): DabServiceInfo? {
        if (ordered.isEmpty()) return null
        val index = ordered.indexOfFirst { it.key == currentKey }
        if (index < 0) return if (direction >= 0) ordered.first() else ordered.last()
        return ordered[Math.floorMod(index + direction, ordered.size)]
    }

    /**
     * The first station (in [byEnsemble] order) of the ensemble [direction] steps away from the one
     * carrying [currentKey], wrapping around.
     */
    fun stepEnsemble(services: List<DabServiceInfo>, currentKey: String?, direction: Int): DabServiceInfo? {
        val groups = byEnsemble(services).groupBy { it.frequencyKHz to it.ensembleId }.values.toList()
        if (groups.isEmpty()) return null
        val index = groups.indexOfFirst { group -> group.any { it.key == currentKey } }
        if (index < 0) return if (direction >= 0) groups.first().first() else groups.last().first()
        return groups[Math.floorMod(index + direction, groups.size)].first()
    }
}
