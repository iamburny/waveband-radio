package com.iamburny.waveband

import com.iamburny.waveband.backend.DabServiceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationSearchTest {

    private val capitalXtra = DabServiceInfo(
        serviceId = 0xC4DA, ensembleId = 0xC1CE, frequencyKHz = 222064, label = "Capital XTRA",
        shortLabel = "CapXTRA", ensembleLabel = "D1 National", programmeType = 10,
    )

    @Test
    fun ignoresCaseSpacesAndPunctuation() {
        assertTrue(StationSearch.matches(capitalXtra, "capital xtra"))
        assertTrue(StationSearch.matches(capitalXtra, "CAPITALXTRA"))
        assertTrue(StationSearch.matches(capitalXtra, "  xtra  "))
    }

    @Test
    fun everyWordMustMatchSomewhere() {
        assertTrue(StationSearch.matches(capitalXtra, "capital d1"))
        assertFalse(StationSearch.matches(capitalXtra, "capital gold"))
    }

    @Test
    fun matchesBlockAndAccents() {
        assertTrue(StationSearch.matches(capitalXtra, "11d"))
        assertTrue(StationSearch.matches(capitalXtra.copy(label = "Radio Café"), "cafe"))
    }

    @Test
    fun emptyQueryMatchesEverything() {
        assertTrue(StationSearch.matches(capitalXtra, ""))
        assertTrue(StationSearch.matches(capitalXtra, "   "))
    }
}
