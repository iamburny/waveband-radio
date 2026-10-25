package com.iamburny.waveband.logos

import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The logos of a RadioDNS Service Information file (ETSI TS 102 818, `SI.xml`), per service.
 * Only `<service>` entries are read; the provider's own logos are ignored.
 */
class ServiceInformation(val services: List<Service>) {

    data class Logo(val url: String, val width: Int, val height: Int)

    /** A service: its bearer URIs (lowercase, such as "dab:ce1.ce15.c222.0") and logos. */
    data class Service(val bearers: List<String>, val logos: List<Logo>)

    /**
     * The service listing [bearer] exactly, or else one with a DAB bearer of the same country
     * and service ID on another ensemble, as stations carried on several multiplexes often list
     * only some of them.
     */
    fun find(bearer: String): Service? {
        val wanted = bearer.lowercase()
        services.firstOrNull { wanted in it.bearers }?.let { return it }
        val parts = wanted.removePrefix("dab:").split('.')
        if (parts.size < 3) return null
        return services.firstOrNull { s ->
            s.bearers.any { b ->
                val p = b.removePrefix("dab:").split('.')
                b.startsWith("dab:") && p.size >= 3 && p[0] == parts[0] && p[2] == parts[2]
            }
        }
    }

    companion object {
        /** Size a stored logo is scaled to fit; also used for media session artwork. */
        const val TARGET_SIZE = 320

        fun parse(input: InputStream): ServiceInformation {
            val bytes = input.readBytes()
            // Android's parser ignores the features below, so documents with a DTD (and with it
            // entity declarations that could expand without limit) are refused up front.
            require(!declaresDoctype(bytes)) { "SI file has a document type declaration" }
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // The file comes from the network: no DTDs or external entities.
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                isExpandEntityReferences = false
            }
            val document = factory.newDocumentBuilder().parse(bytes.inputStream())
            val nodes = document.getElementsByTagNameNS("*", "service")
            val services = (0 until nodes.length).map { i ->
                val service = nodes.item(i) as Element
                Service(
                    bearers = service.children("bearer").mapNotNull { b -> b.getAttribute("id").trim().lowercase().ifEmpty { null } },
                    logos = service.children("mediaDescription").flatMap { it.children("multimedia") }.mapNotNull(::logo),
                )
            }
            return ServiceInformation(services)
        }

        /**
         * The logo to download: a square one if there is any, the smallest at least [TARGET_SIZE]
         * wide, or else the largest.
         */
        fun bestLogo(logos: List<Logo>): Logo? {
            val pool = logos.filter { it.width == it.height }.ifEmpty { logos }
            return pool.filter { it.width >= TARGET_SIZE }.minByOrNull { it.width } ?: pool.maxByOrNull { it.width }
        }

        private fun declaresDoctype(bytes: ByteArray): Boolean {
            val utf16 = bytes.size >= 2 &&
                ((bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) || (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()))
            val text = if (utf16) String(bytes, Charsets.UTF_16) else String(bytes, Charsets.UTF_8)
            return text.contains("<!DOCTYPE", ignoreCase = true)
        }

        private fun logo(element: Element): Logo? {
            val url = element.getAttribute("url").trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null
            // Older files size their logos by type instead of width and height.
            return when (element.getAttribute("type")) {
                "logo_colour_square" -> Logo(url, 32, 32)
                "logo_colour_rectangle" -> Logo(url, 112, 32)
                else -> {
                    val width = element.getAttribute("width").toIntOrNull() ?: return null
                    val height = element.getAttribute("height").toIntOrNull() ?: return null
                    Logo(url, width, height)
                }
            }
        }

        private fun Element.children(localName: String): List<Element> {
            val list = ArrayList<Element>()
            var node = firstChild
            while (node != null) {
                if (node is Element && (node.localName ?: node.nodeName) == localName) list += node
                node = node.nextSibling
            }
            return list
        }
    }
}
