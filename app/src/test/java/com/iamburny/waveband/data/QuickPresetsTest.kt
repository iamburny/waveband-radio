package com.iamburny.waveband.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickPresetsTest {

    private val dab1 = "C221ABCD@1"
    private val dab2 = "C221ABCE@1"
    private val presets = listOf(dab1, "fm:8950", null, "am:909", dab2, "fm:9390", null, null, null, null, null, null)

    @Test
    fun kindOfKey() {
        assertEquals(PresetKind.DAB, PresetKind.of(dab1))
        assertEquals(PresetKind.FM, PresetKind.of("fm:8950"))
        assertEquals(PresetKind.AM, PresetKind.of("am:909"))
    }

    @Test
    fun slotsShowPresetsOfTheKindThenFreeSlots() {
        assertEquals(listOf(0, 4, 2, 6, 7, 8), QuickPresets.slots(presets, PresetKind.DAB))
        assertEquals(listOf(1, 5, 2, 6, 7, 8), QuickPresets.slots(presets, PresetKind.FM))
        assertEquals(listOf(3, 2, 6, 7, 8, 9), QuickPresets.slots(presets, PresetKind.AM))
    }

    @Test
    fun slotsRunOutWhenNothingIsFree() {
        val full = List(12) { if (it < 2) "fm:${8950 + it * 10}" else "C221ABC$it@1" }
        assertEquals(listOf(0, 1, null, null, null, null), QuickPresets.slots(full, PresetKind.FM))
        assertEquals(listOf(2, 3, 4, 5, 6, 7), QuickPresets.slots(full, PresetKind.DAB))
    }

    @Test
    fun stepWrapsWithinTheKind() {
        assertEquals("fm:9390", QuickPresets.step(presets, PresetKind.FM, "fm:8950", 1))
        assertEquals("fm:8950", QuickPresets.step(presets, PresetKind.FM, "fm:9390", 1))
        assertEquals("fm:9390", QuickPresets.step(presets, PresetKind.FM, "fm:8950", -1))
        assertEquals("fm:8950", QuickPresets.step(presets, PresetKind.FM, "fm:10000", 1))
        assertEquals("fm:9390", QuickPresets.step(presets, PresetKind.FM, null, -1))
        assertEquals("am:909", QuickPresets.step(presets, PresetKind.AM, "am:909", 1))
        assertNull(QuickPresets.step(listOf(dab1, null), PresetKind.FM, null, 1))
        assertEquals("fm:9390", QuickPresets.step(listOf("fm:8950", "fm:8950", "fm:9390"), PresetKind.FM, "fm:8950", 1))
    }
}
