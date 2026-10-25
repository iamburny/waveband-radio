package com.iamburny.waveband

import com.iamburny.waveband.backend.DabServiceInfo
import com.iamburny.waveband.backend.ProgrammeType
import java.text.Normalizer

/**
 * Station list search. Matching ignores case, accents, spaces and punctuation, so "capitalxtra"
 * and "capital xtra" both find "Capital XTRA". Every word of the query must appear in the
 * station name, short name, ensemble, programme type or block.
 */
object StationSearch {

    private val marks = Regex("\\p{M}+")

    fun matches(info: DabServiceInfo, query: String): Boolean {
        val words = query.split(Regex("\\s+")).map(::normalise).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val fields = listOfNotNull(info.label, info.shortLabel, info.ensembleLabel, ProgrammeType.name(info.programmeType), info.channelLabel)
            .map(::normalise)
        return words.all { word -> fields.any { it.contains(word) } }
    }

    private fun normalise(text: String): String =
        marks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase().filter { it.isLetterOrDigit() }
}
