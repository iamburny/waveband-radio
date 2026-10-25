package com.iamburny.waveband

import org.junit.Assert.assertEquals
import org.junit.Test

class MonogramTest {

    @Test
    fun initialsOfTwoWords() {
        assertEquals("CJ", Monogram.initials("Coastline Jazz"))
        assertEquals("GH", Monogram.initials("Greatest Hits Radio"))
    }

    @Test
    fun numbersAreKept() {
        assertEquals("R2", Monogram.initials("Radio 2"))
        assertEquals("A80", Monogram.initials("Absolute 80"))
        assertEquals("A8", Monogram.initials("Absolute 80s"))
    }

    @Test
    fun shortUppercaseNamesStayWhole() {
        assertEquals("LBC", Monogram.initials("LBC"))
        assertEquals("TA", Monogram.initials("talkSPORT"))
    }

    @Test
    fun colourIsStablePerService() {
        assertEquals(Monogram.colour(0xC221), Monogram.colour(0xC221))
    }
}
