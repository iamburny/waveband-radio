package com.iamburny.waveband.fm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The head unit's own FM/AM tuner. Its audio doesn't pass through Android: while [activate]d the
 * head unit switches its speakers to the tuner, and [deactivate] hands them back.
 *
 * Commands are ignored until [FmState.connected], and dropped if [disconnect] comes first. Call
 * [connect] and [disconnect] on the main thread; the other calls may come from any thread.
 * [disconnect] switches the tuner off if we switched it on.
 */
interface HeadUnitRadio {

    val state: StateFlow<FmState>

    /** Why the tuner can't be used, or null while it can. */
    val problem: StateFlow<String?>

    /**
     * Next (+1) and previous (-1) presses of the head unit's own keys, such as the steering wheel's,
     * while the tuner is ours. With nobody collecting, they step through the tuner's presets.
     */
    val skipRequests: Flow<Int>

    fun connect()

    fun disconnect()

    /** Re-reads everything from the tuner. */
    fun refresh()

    /**
     * Puts the tuner on the speakers, switching to [band] if given, and then tuning to
     * [frequency] (in [band]) if given.
     */
    fun activate(band: FmBand? = null, frequency: Int? = null)

    fun deactivate()

    fun selectBand(band: FmBand)

    /** Searches for the next station up (+1) or down (-1). */
    fun seek(direction: Int)

    /** Moves one channel step up (+1) or down (-1). */
    fun step(direction: Int)

    fun tune(band: FmBand, frequency: Int)

    /** [slot] 0-5 of the current band. */
    fun recallPreset(slot: Int)

    /** Recalls the next (+1) or previous (-1) preset of the current band, wrapping round. */
    fun stepPreset(direction: Int) {
        val slot = state.value.presetSlot
        recallPreset(
            if (slot < 0) (if (direction > 0) 0 else FmCodec.PRESETS_PER_BAND - 1)
            else Math.floorMod(slot + direction, FmCodec.PRESETS_PER_BAND),
        )
    }

    /** Switches between Local, which only stops on strong stations, and DX (distant). */
    fun toggleLocal()

    /** Saves the current frequency to [slot] 0-5 of the current band. */
    fun storePreset(slot: Int)
}
