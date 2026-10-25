package com.iamburny.waveband.logos

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class DnsMessageTest {

    @Test
    fun buildsAQuery() {
        val query = DnsMessage.query(0x1234, "a.bc", DnsMessage.TYPE_SRV)
        val expected = byteArrayOf(
            0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0,
            1, 'a'.code.toByte(), 2, 'b'.code.toByte(), 'c'.code.toByte(), 0,
            0, 33, 0, 1,
        )
        assertArrayEquals(expected, query)
    }

    @Test
    fun readsCname() {
        val name = "0.c222.ce15.ce1.dab.radiodns.org"
        val message = response(name, DnsMessage.TYPE_CNAME) { out -> out.name("radiodns.api.bbci.co.uk") }
        assertEquals(listOf("radiodns.api.bbci.co.uk"), DnsMessage.cnames(message))
        assertEquals(0, DnsMessage.responseCode(message))
    }

    @Test
    fun readsSrvRecordsBestFirst() {
        val message = response("_radiospi._tcp.example.org", DnsMessage.TYPE_SRV, answers = 2) { out ->
            out.srv(priority = 20, weight = 0, port = 80, target = "backup.example.org")
        }.let { appendAnswer(it, DnsMessage.TYPE_SRV) { out -> out.srv(10, 5, 443, "spi.example.org") } }
        val srvs = DnsMessage.srvs(message)
        assertEquals(listOf("spi.example.org", "backup.example.org"), srvs.map { it.target })
        assertEquals(443, srvs.first().port)
    }

    @Test
    fun answerNameMayPointAtTheQuestion() {
        val message = response("x.example.org", DnsMessage.TYPE_CNAME) { out ->
            // Label "cdn" followed by a pointer to "example.org" in the question (offset 12 + 2).
            out.write(3)
            out.write("cdn".toByteArray())
            out.write(0xC0)
            out.write(14)
        }
        assertEquals(listOf("cdn.example.org"), DnsMessage.cnames(message))
    }

    /** A response with one question for [name] and [answers] answer records, the first written by [rdata]. */
    private fun response(name: String, type: Int, answers: Int = 1, rdata: (ByteArrayOutputStream) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, answers.toByte(), 0, 0, 0, 0))
        out.name(name)
        out.u16(type)
        out.u16(DnsMessage.CLASS_IN)
        return appendRecord(out, type, rdata)
    }

    private fun appendAnswer(message: ByteArray, type: Int, rdata: (ByteArrayOutputStream) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(message)
        return appendRecord(out, type, rdata)
    }

    private fun appendRecord(out: ByteArrayOutputStream, type: Int, rdata: (ByteArrayOutputStream) -> Unit): ByteArray {
        // Owner name: a pointer to the question name.
        out.write(0xC0)
        out.write(12)
        out.u16(type)
        out.u16(DnsMessage.CLASS_IN)
        out.write(byteArrayOf(0, 0, 0x0E, 0x10))
        val data = ByteArrayOutputStream().also(rdata).toByteArray()
        out.u16(data.size)
        out.write(data)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.u16(v: Int) {
        write(v shr 8 and 0xFF)
        write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.name(name: String) {
        for (label in name.split('.')) {
            write(label.length)
            write(label.toByteArray())
        }
        write(0)
    }

    private fun ByteArrayOutputStream.srv(priority: Int, weight: Int, port: Int, target: String) {
        u16(priority)
        u16(weight)
        u16(port)
        name(target)
    }
}
