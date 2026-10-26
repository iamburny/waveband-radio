package com.iamburny.waveband.fm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FmCodecTest {

    @Test
    fun mapsBandNumbers() {
        assertEquals(FmBand.FM, FmCodec.bandOf(0))
        assertEquals(FmBand.FM, FmCodec.bandOf(2))
        assertEquals(FmBand.AM, FmCodec.bandOf(3))
        assertEquals(FmBand.AM, FmCodec.bandOf(4))
        assertNull(FmCodec.bandOf(5))
        assertNull(FmCodec.bandOf(-1))
    }

    @Test
    fun labelsFrequencies() {
        assertEquals("87.50 MHz", FmCodec.label(FmBand.FM, 8750))
        assertEquals("98.10 MHz", FmCodec.label(FmBand.FM, 9810))
        assertEquals("104.05 MHz", FmCodec.label(FmBand.FM, 10405))
        assertEquals("1089 kHz", FmCodec.label(FmBand.AM, 1089))
    }

    @Test
    fun validatesRanges() {
        assertTrue(FmCodec.isValid(FmBand.FM, 8750))
        assertTrue(FmCodec.isValid(FmBand.FM, 10800))
        assertFalse(FmCodec.isValid(FmBand.FM, 1089))
        assertFalse(FmCodec.isValid(FmBand.FM, 0))
        assertTrue(FmCodec.isValid(FmBand.AM, 531))
        assertFalse(FmCodec.isValid(FmBand.AM, 8750))
    }

    @Test
    fun slicesPresetsPerBand() {
        val list = List(42) { it }
        assertEquals(listOf(0, 1, 2, 3, 4, 5), FmCodec.presetsOf(0, list))
        assertEquals(listOf(18, 19, 20, 21, 22, 23), FmCodec.presetsOf(3, list))
        assertEquals(emptyList<Int>(), FmCodec.presetsOf(5, list))
        assertEquals(emptyList<Int>(), FmCodec.presetsOf(0, emptyList()))
    }
}
