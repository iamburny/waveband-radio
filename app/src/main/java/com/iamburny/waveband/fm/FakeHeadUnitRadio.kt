package com.iamburny.waveband.fm

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.iamburny.waveband.playback.AudioFocus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Simulated head unit tuner for debug builds on devices without one, so the FM/AM screens can be
 * tried on an emulator. It makes no sound, but takes media audio focus like the real one, so
 * switching between DAB and FM behaves the same.
 */
class FakeHeadUnitRadio(context: Context) : HeadUnitRadio {

    private val main = Handler(Looper.getMainLooper())
    private val focus = AudioFocus(context.applicationContext, Looper.getMainLooper()) { lostForGood ->
        if (lostForGood) _state.update { it.copy(active = false, ours = false) }
    }
    private val _state = MutableStateFlow(FmState())
    override val state: StateFlow<FmState> = _state.asStateFlow()
    override val problem: StateFlow<String?> = MutableStateFlow(null)
    override val skipRequests: Flow<Int> = emptyFlow()

    override fun connect() {
        _state.value = FmState(
            connected = true,
            bandNumber = 0,
            frequency = 8950,
            presetSlot = 0,
            stationName = NAMES[8950].orEmpty(),
            stereo = true,
            rds = true,
            presets = listOf(
                8950, 9170, 9390, 9520, 9910, 10130,
                8750, 9000, 9800, 10430, 10800, 8750,
                8750, 9000, 9800, 10430, 10800, 8750,
                909, 693, 1089, 1215, 1458, 198,
                522, 603, 999, 1404, 1620, 522,
            ),
        )
    }

    override fun disconnect() {
        main.post { focus.abandon() }
        _state.update { it.copy(connected = false, active = false, ours = false) }
    }

    override fun refresh() = Unit

    override fun activate(band: FmBand?, frequency: Int?) {
        main.post {
            if (!_state.value.connected || !focus.request()) return@post
            if (band != null) selectBand(band)
            if (band != null && frequency != null) tune(band, frequency)
            _state.update { it.copy(active = true, ours = true) }
        }
    }

    override fun deactivate() {
        main.post { focus.abandon() }
        _state.update { it.copy(active = false, ours = false) }
    }

    override fun selectBand(band: FmBand) {
        if (_state.value.band == band) return
        val bandNumber = if (band == FmBand.FM) 0 else 3
        tuneTo(bandNumber, FmCodec.presetsOf(bandNumber, _state.value.presets).first(), slot = 0)
    }

    override fun seek(direction: Int) {
        val s = _state.value
        val band = s.band ?: return
        val known = NAMES.keys.filter { FmCodec.isValid(band, it) }.sorted()
        val next = if (direction > 0) known.firstOrNull { it > s.frequency } ?: known.first()
        else known.lastOrNull { it < s.frequency } ?: known.last()
        tuneTo(s.bandNumber, next)
    }

    override fun step(direction: Int) {
        val s = _state.value
        val band = s.band ?: return
        val range = if (band == FmBand.FM) FM_TUNING else AM_TUNING
        val stepSize = if (band == FmBand.FM) 5 else 9
        val next = (s.frequency + direction * stepSize).let { if (it > range.last) range.first else if (it < range.first) range.last else it }
        tuneTo(s.bandNumber, next)
    }

    override fun tune(band: FmBand, frequency: Int) {
        require(FmCodec.isValid(band, frequency)) { "$frequency is not a valid $band frequency" }
        tuneTo(if (band == FmBand.FM) 0 else 3, frequency)
    }

    override fun toggleLocal() = _state.update { it.copy(local = !it.local) }

    override fun recallPreset(slot: Int) {
        val s = _state.value
        val frequency = s.bandPresets.getOrNull(slot) ?: return
        tuneTo(s.bandNumber, frequency, slot)
    }

    override fun storePreset(slot: Int) {
        val s = _state.value
        if (slot !in 0 until FmCodec.PRESETS_PER_BAND || s.bandPresets.isEmpty()) return
        val presets = s.presets.toMutableList()
        presets[s.bandNumber * FmCodec.PRESETS_PER_BAND + slot] = s.frequency
        _state.update { it.copy(presets = presets, presetSlot = slot) }
    }

    private fun tuneTo(bandNumber: Int, frequency: Int, slot: Int = -1) {
        val name = NAMES[frequency].orEmpty()
        _state.update {
            it.copy(
                bandNumber = bandNumber,
                frequency = frequency,
                presetSlot = slot,
                stationName = name,
                stereo = name.isNotEmpty() && bandNumber < 3,
                rds = name.isNotEmpty() && bandNumber < 3,
                trafficProgramme = name.startsWith("BBC"),
            )
        }
    }

    private companion object {
        val FM_TUNING = 8750..10800
        val AM_TUNING = 522..1620
        val NAMES = mapOf(
            8950 to "BBC R2", 9170 to "BBC R3", 9390 to "BBC R4", 9520 to "Capital",
            9910 to "BBC R1", 10130 to "Classic", 10430 to "Heart", 10800 to "Smooth",
            909 to "BBC 5L", 693 to "BBC 5L", 1089 to "TalkSPORT", 1215 to "Absolute", 198 to "BBC R4 LW",
        )
    }
}
