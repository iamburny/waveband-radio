package com.iamburny.waveband.logos

import com.iamburny.waveband.backend.DabServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioDnsTest {

    private val radio2 = DabServiceInfo(serviceId = 0xC222, ensembleId = 0xCE15, frequencyKHz = 225648, label = "BBC Radio 2", ensembleEcc = 0xE1)

    @Test
    fun namesAUkService() {
        assertEquals("0.c222.ce15.ce1.dab.radiodns.org", RadioDns.lookupName(radio2))
        assertEquals("dab:ce1.ce15.c222.0", RadioDns.bearerUri(radio2))
        assertEquals("ce1.c222", RadioDns.logoKey(radio2))
    }

    @Test
    fun ukServiceWithoutEccFallsBackToUk() {
        assertEquals("0.c222.ce15.ce1.dab.radiodns.org", RadioDns.lookupName(radio2.copy(ensembleEcc = 0)))
    }

    @Test
    fun otherCountryUsesItsEcc() {
        val bayern3 = DabServiceInfo(serviceId = 0xD313, ensembleId = 0x10A5, frequencyKHz = 202928, label = "Bayern 3", ensembleEcc = 0xE0)
        assertEquals("0.d313.10a5.de0.dab.radiodns.org", RadioDns.lookupName(bayern3))
    }

    @Test
    fun cannotNameWithoutCountry() {
        val unknown = DabServiceInfo(serviceId = 0xD313, ensembleId = 0x10A5, frequencyKHz = 202928, label = "Bayern 3")
        assertNull(RadioDns.lookupName(unknown))
        assertNull(RadioDns.logoKey(unknown))
    }

    @Test
    fun cannotNameDataServiceIds() {
        assertNull(RadioDns.lookupName(radio2.copy(serviceId = 0xE1C22201L)))
    }
}
