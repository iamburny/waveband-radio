package com.iamburny.waveband.logos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceInformationTest {

    private val xml = """
        <?xml version="1.0" encoding="utf-8"?>
        <serviceInformation xmlns="http://www.worlddab.org/schemas/spi">
          <services>
            <serviceProvider>
              <mediaDescription>
                <multimedia type="logo_unrestricted" url="https://example.org/provider_600.png" width="600" height="600"/>
              </mediaDescription>
            </serviceProvider>
            <service>
              <mediaDescription>
                <multimedia type="logo_unrestricted" url="https://example.org/r2_128.png" width="128" height="128"/>
              </mediaDescription>
              <mediaDescription>
                <multimedia type="logo_unrestricted" url="https://example.org/r2_320x240.png" width="320" height="240"/>
              </mediaDescription>
              <mediaDescription>
                <multimedia type="logo_unrestricted" url="https://example.org/r2_600.png" width="600" height="600"/>
              </mediaDescription>
              <mediaDescription>
                <multimedia type="logo_unrestricted" url="https://example.org/r2_1400.png" width="1400" height="1400"/>
              </mediaDescription>
              <bearer id="dab:ce1.ce15.c222.0" cost="20"/>
              <bearer id="fm:ce1.c202.08810" cost="30"/>
            </service>
            <service>
              <mediaDescription>
                <multimedia type="logo_colour_square" url="http://example.org/heart_32.png"/>
              </mediaDescription>
              <bearer id="DAB:CE1.C1CE.C0C6.0"/>
            </service>
          </services>
        </serviceInformation>
    """.trimIndent()

    private val si = ServiceInformation.parse(xml.byteInputStream())

    @Test
    fun readsServicesButNotTheProvider() {
        assertEquals(2, si.services.size)
        assertEquals(listOf("dab:ce1.ce15.c222.0", "fm:ce1.c202.08810"), si.services[0].bearers)
        assertEquals(4, si.services[0].logos.size)
    }

    @Test
    fun picksTheSmallestSquareLogoThatIsLargeEnough() {
        val logo = ServiceInformation.bestLogo(si.find("dab:ce1.ce15.c222.0")!!.logos)
        assertEquals("https://example.org/r2_600.png", logo?.url)
    }

    @Test
    fun sizesLegacyLogoTypes() {
        val logos = si.find("dab:ce1.c1ce.c0c6.0")!!.logos
        assertEquals(listOf(ServiceInformation.Logo("http://example.org/heart_32.png", 32, 32)), logos)
    }

    @Test
    fun matchesTheSameServiceOnAnotherEnsemble() {
        assertEquals(si.services[1], si.find("dab:ce1.c181.c0c6.0"))
        assertNull(si.find("dab:ce1.c181.c0c7.0"))
    }

    @Test
    fun readsLatin1Files() {
        val latin1 = """<?xml version="1.0" encoding="ISO-8859-1"?><serviceInformation><services><service>""" +
            """<bearer id="dab:ce1.ce15.c222.0"/></service></services></serviceInformation>"""
        assertEquals(1, ServiceInformation.parse(latin1.toByteArray(Charsets.ISO_8859_1).inputStream()).services.size)
    }

    @Test
    fun rejectsDoctypesInOtherEncodings() {
        val hostile = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e "e">]><serviceInformation/>"""
        assertEquals(true, runCatching { ServiceInformation.parse(hostile.toByteArray(Charsets.UTF_16LE).inputStream()) }.isFailure)
        val declared = """<?xml version="1.0" encoding="UTF-16"?><serviceInformation/>"""
        assertEquals(true, runCatching { ServiceInformation.parse(declared.byteInputStream()) }.isFailure)
    }

    @Test
    fun rejectsDoctypes() {
        val hostile = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><serviceInformation/>"""
        val result = runCatching { ServiceInformation.parse(hostile.byteInputStream()) }
        assertEquals(true, result.isFailure)
    }
}
