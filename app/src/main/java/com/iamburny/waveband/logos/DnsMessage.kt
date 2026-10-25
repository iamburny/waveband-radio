package com.iamburny.waveband.logos

import java.io.ByteArrayOutputStream

/** The parts of the DNS wire format (RFC 1035, SRV from RFC 2782) a RadioDNS lookup needs. */
object DnsMessage {

    const val TYPE_CNAME = 5
    const val TYPE_SRV = 33
    const val CLASS_IN = 1

    data class Srv(val priority: Int, val weight: Int, val port: Int, val target: String)

    /** A recursive query for [name] of record [type]. */
    fun query(id: Int, name: String, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) {
            out.write(v shr 8 and 0xFF)
            out.write(v and 0xFF)
        }
        u16(id)
        u16(0x0100) // Recursion desired.
        u16(1)
        u16(0)
        u16(0)
        u16(0)
        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63) { "Bad DNS label in $name" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        u16(type)
        u16(CLASS_IN)
        return out.toByteArray()
    }

    /** Targets of the CNAME records in the answer section. */
    fun cnames(message: ByteArray): List<String> =
        answers(message, TYPE_CNAME).map { (offset, _) -> readName(message, offset).first }

    /** SRV records in the answer section, best first (lowest priority, then highest weight). */
    fun srvs(message: ByteArray): List<Srv> =
        answers(message, TYPE_SRV).map { (offset, length) ->
            require(length >= 7) { "Short SRV record" }
            Srv(u16(message, offset), u16(message, offset + 2), u16(message, offset + 4), readName(message, offset + 6).first)
        }.sortedWith(compareBy<Srv> { it.priority }.thenByDescending { it.weight })

    /** Response code of the header; 0 is success, 3 means the name does not exist. */
    fun responseCode(message: ByteArray): Int {
        require(message.size >= 12) { "Short DNS message" }
        return message[3].toInt() and 0x0F
    }

    fun id(message: ByteArray): Int {
        require(message.size >= 2) { "Short DNS message" }
        return u16(message, 0)
    }

    /** (rdata offset, rdata length) of each answer record of [type]. */
    private fun answers(message: ByteArray, type: Int): List<Pair<Int, Int>> {
        require(message.size >= 12) { "Short DNS message" }
        val questions = u16(message, 4)
        val answerCount = u16(message, 6)
        var offset = 12
        repeat(questions) { offset = readName(message, offset).second + 4 }
        val found = ArrayList<Pair<Int, Int>>()
        repeat(answerCount) {
            offset = readName(message, offset).second
            val recordType = u16(message, offset)
            val length = u16(message, offset + 8)
            val data = offset + 10
            require(data + length <= message.size) { "Truncated DNS record" }
            if (recordType == type) found += data to length
            offset = data + length
        }
        return found
    }

    /** Reads a possibly compressed name at [start]; returns it and the offset just past it. */
    private fun readName(message: ByteArray, start: Int): Pair<String, Int> {
        val labels = ArrayList<String>()
        var offset = start
        var end = -1
        var jumps = 0
        while (true) {
            require(offset < message.size) { "Truncated DNS name" }
            val length = message[offset].toInt() and 0xFF
            when {
                length == 0 -> {
                    if (end < 0) end = offset + 1
                    break
                }
                length and 0xC0 == 0xC0 -> {
                    require(offset + 1 < message.size && ++jumps <= 32) { "Bad DNS name pointer" }
                    if (end < 0) end = offset + 2
                    offset = (length and 0x3F) shl 8 or (message[offset + 1].toInt() and 0xFF)
                }
                else -> {
                    require(offset + 1 + length <= message.size) { "Truncated DNS label" }
                    labels += String(message, offset + 1, length, Charsets.US_ASCII)
                    offset += 1 + length
                }
            }
        }
        return labels.joinToString(".") to end
    }

    private fun u16(message: ByteArray, offset: Int): Int {
        require(offset + 1 < message.size) { "Truncated DNS message" }
        return (message[offset].toInt() and 0xFF) shl 8 or (message[offset + 1].toInt() and 0xFF)
    }
}
