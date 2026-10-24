package com.matt.dabtuner.backend

/** International DAB programme types (ETSI TS 101 756 table 12) and the Music/Talk split the station list filters on. */
object ProgrammeType {

    enum class Kind { MUSIC, TALK, NONE }

    private val names = arrayOf(
        "", "News", "Current Affairs", "Information", "Sport", "Education", "Drama", "Culture",
        "Science", "Varied", "Pop", "Rock", "Easy Listening", "Light Classical", "Classical",
        "Other Music", "Weather", "Finance", "Children's", "Social Affairs", "Religion", "Phone-in",
        "Travel", "Leisure", "Jazz", "Country", "National Music", "Oldies", "Folk", "Documentary",
    )

    private val music = setOf(10, 11, 12, 13, 14, 15, 24, 25, 26, 27, 28)

    /** Display name of [code], or null for "no programme type" and undefined codes. */
    fun name(code: Int): String? = names.getOrNull(code)?.takeIf { it.isNotEmpty() }

    fun kind(code: Int): Kind = when {
        name(code) == null -> Kind.NONE
        code in music -> Kind.MUSIC
        else -> Kind.TALK
    }
}
