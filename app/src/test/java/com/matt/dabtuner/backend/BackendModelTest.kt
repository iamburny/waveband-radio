package com.matt.dabtuner.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackendModelTest {

    @Test
    fun signalBars() {
        assertEquals(0, SignalInfo(locked = false, signalLevel = 100, snrDb = null).bars)
        assertEquals(1, SignalInfo(locked = true, signalLevel = 0, snrDb = null).bars)
        assertEquals(2, SignalInfo(locked = true, signalLevel = 33, snrDb = null).bars)
        assertEquals(3, SignalInfo(locked = true, signalLevel = 66, snrDb = null).bars)
        assertEquals(4, SignalInfo(locked = true, signalLevel = 100, snrDb = null).bars)
    }

    @Test
    fun programmeTypes() {
        assertEquals("Pop", ProgrammeType.name(10))
        assertNull(ProgrammeType.name(0))
        assertNull(ProgrammeType.name(31))
        assertEquals(ProgrammeType.Kind.MUSIC, ProgrammeType.kind(24))
        assertEquals(ProgrammeType.Kind.TALK, ProgrammeType.kind(1))
        assertEquals(ProgrammeType.Kind.NONE, ProgrammeType.kind(0))
    }

    @Test
    fun channelLabel() {
        assertEquals("11D", DabServiceInfo(1, 1, 222064, "x").channelLabel)
        assertEquals("100.000 MHz", DabServiceInfo(1, 1, 100000, "x").channelLabel)
    }
}
