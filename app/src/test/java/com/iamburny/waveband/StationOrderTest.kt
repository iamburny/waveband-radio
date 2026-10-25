package com.iamburny.waveband

import com.iamburny.waveband.backend.DabServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationOrderTest {

    private fun service(id: Long, label: String, ensemble: String, frequency: Int) =
        DabServiceInfo(serviceId = id, ensembleId = frequency, frequencyKHz = frequency, label = label, ensembleLabel = ensemble)

    private val bravo = service(1, "Bravo", "North", 222064)
    private val alpha = service(2, "alpha", "North", 222064)
    private val zulu = service(3, "Zulu", "Central", 225648)
    private val echo = service(4, "Echo", "South", 213360)
    private val all = listOf(bravo, alpha, zulu, echo)

    @Test
    fun byEnsembleGroupsByEnsembleThenName() {
        assertEquals(listOf(zulu, alpha, bravo, echo), StationOrder.byEnsemble(all))
    }

    @Test
    fun alphabeticalIgnoresCase() {
        assertEquals(listOf(alpha, bravo, echo, zulu), StationOrder.alphabetical(all))
    }

    @Test
    fun stepWrapsAround() {
        val ordered = StationOrder.byEnsemble(all)
        assertEquals(zulu, StationOrder.step(ordered, echo.key, 1))
        assertEquals(echo, StationOrder.step(ordered, zulu.key, -1))
        assertEquals(bravo, StationOrder.step(ordered, alpha.key, 1))
    }

    @Test
    fun stepWithoutCurrentStartsAtAnEnd() {
        val ordered = StationOrder.byEnsemble(all)
        assertEquals(zulu, StationOrder.step(ordered, null, 1))
        assertEquals(echo, StationOrder.step(ordered, null, -1))
        assertNull(StationOrder.step(emptyList(), null, 1))
    }

    @Test
    fun stepEnsembleJumpsToFirstStationOfNeighbouringEnsemble() {
        assertEquals(echo, StationOrder.stepEnsemble(all, bravo.key, 1))
        assertEquals(zulu, StationOrder.stepEnsemble(all, bravo.key, -1))
        assertEquals(alpha, StationOrder.stepEnsemble(all, zulu.key, 1))
        assertEquals(zulu, StationOrder.stepEnsemble(all, echo.key, 1))
    }
}
