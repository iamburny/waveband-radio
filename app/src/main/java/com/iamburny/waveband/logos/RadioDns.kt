package com.iamburny.waveband.logos

import com.iamburny.waveband.backend.DabServiceInfo

/**
 * RadioDNS names for DAB services (ETSI TS 103 270). A service is identified by its global
 * country code (the country nibble of the service ID followed by the ensemble's extended
 * country code), ensemble ID, service ID and service component ID.
 */
object RadioDns {

    /** ECC of the United Kingdom, the only region the app tunes. */
    private const val UK_ECC = 0xE1

    /** Country nibble shared by UK service IDs. */
    private const val UK_COUNTRY = 0xC

    /**
     * Global country code such as "ce1", or null when it cannot be worked out. Some ensembles do
     * not signal their ECC; a UK country nibble then falls back to the UK ECC.
     */
    fun globalCountryCode(info: DabServiceInfo): String? {
        if (info.serviceId !in 0x1000L..0xFFFFL) return null
        val country = (info.serviceId shr 12).toInt()
        val ecc = when {
            info.ensembleEcc != 0 -> info.ensembleEcc
            country == UK_COUNTRY -> UK_ECC
            else -> return null
        }
        return "%x%02x".format(country, ecc)
    }

    /** Lookup name such as "0.c222.ce15.ce1.dab.radiodns.org", or null for services it cannot name. */
    fun lookupName(info: DabServiceInfo): String? {
        val gcc = globalCountryCode(info) ?: return null
        return "0.%04x.%04x.%s.dab.radiodns.org".format(info.serviceId, info.ensembleId and 0xFFFF, gcc)
    }

    /**
     * Key a logo is cached under, such as "ce1.c222". It leaves out the ensemble, so a station
     * carried on several multiplexes shares one logo.
     */
    fun logoKey(info: DabServiceInfo): String? = globalCountryCode(info)?.let { "%s.%04x".format(it, info.serviceId) }

    /**
     * SI file URL served by the host of an SPI [srv] record, or null when the record's target is
     * "." (the service is deliberately not offered).
     */
    fun serviceInformationUrl(srv: DnsMessage.Srv): String? {
        val host = srv.target.trimEnd('.')
        if (host.isEmpty()) return null
        return when (srv.port) {
            443 -> "https://$host/radiodns/spi/3.1/SI.xml"
            80 -> "http://$host/radiodns/spi/3.1/SI.xml"
            else -> "http://$host:${srv.port}/radiodns/spi/3.1/SI.xml"
        }
    }

    /** Bearer URI an SI file lists for the service, such as "dab:ce1.ce15.c222.0". */
    fun bearerUri(info: DabServiceInfo): String? {
        val gcc = globalCountryCode(info) ?: return null
        return "dab:%s.%04x.%04x.0".format(gcc, info.ensembleId and 0xFFFF, info.serviceId)
    }
}
