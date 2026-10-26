package com.iamburny.waveband.fm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FmKeyTest {

    @Test
    fun roundTrips() {
        assertEquals("fm:8950", FmKey.of(FmBand.FM, 8950))
        assertEquals("am:909", FmKey.of(FmBand.AM, 909))
        assertEquals(FmBand.FM to 8950, FmKey.parse("fm:8950"))
        assertEquals(FmBand.AM to 909, FmKey.parse("am:909"))
    }

    @Test
    fun rejectsDabKeysAndBadFrequencies() {
        assertNull(FmKey.parse("e1.c221.0"))
        assertNull(FmKey.parse("c221"))
        assertNull(FmKey.parse("fm:"))
        assertNull(FmKey.parse("fm:abc"))
        assertNull(FmKey.parse("fm:909"))
        assertNull(FmKey.parse("am:8950"))
        assertNull(FmKey.parse("dab:8950"))
    }

    @Test
    fun stateKeyNeedsAKnownBandAndValidFrequency() {
        assertEquals("fm:9910", FmState(bandNumber = 1, frequency = 9910).key)
        assertEquals("am:1089", FmState(bandNumber = 4, frequency = 1089).key)
        assertNull(FmState(bandNumber = 6, frequency = 9910).key)
        assertNull(FmState(bandNumber = 0, frequency = 0).key)
    }
}
