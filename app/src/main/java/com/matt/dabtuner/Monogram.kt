package com.matt.dabtuner

/**
 * Generated station logo: up to three initials on a colour picked from the service ID, so a
 * station keeps the same tile everywhere (lists, presets, media session artwork).
 */
object Monogram {

    /** Dark tones that keep white initials above 4.5:1 contrast. */
    private val colours = intArrayOf(
        0xFF6D3FD1.toInt(), 0xFFB5361C.toInt(), 0xFF1F5F66.toInt(), 0xFF8A2F6B.toInt(),
        0xFFB0223A.toInt(), 0xFF2B2F36.toInt(), 0xFF7A4A12.toInt(), 0xFF1D4E9E.toInt(),
        0xFF5E1F1F.toInt(), 0xFF0F4C81.toInt(), 0xFF4B3B8F.toInt(),
    )

    fun colour(serviceId: Long): Int {
        // Mix the bits so services with neighbouring IDs still get different colours.
        var h = serviceId xor (serviceId ushr 16)
        h *= 0x45D9F3B
        h = h xor (h ushr 16)
        return colours[Math.floorMod(h, colours.size.toLong()).toInt()]
    }

    fun initials(label: String): String {
        val words = label.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return "?"
        val first = words[0]
        if (words.size == 1) {
            return if (first.length <= 3 && first == first.uppercase()) first else first.take(2).uppercase()
        }
        val second = words[1]
        val tail = if (second.all { it.isDigit() }) second.take(2) else second.take(1)
        return (first.take(1) + tail).uppercase()
    }
}
