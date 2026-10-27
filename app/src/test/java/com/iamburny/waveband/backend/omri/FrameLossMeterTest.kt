package com.iamburny.waveband.backend.omri

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameLossMeterTest {

    private val second = 1_000_000_000L

    private fun FrameLossMeter.feed(rate: Double, seconds: Int, keepEvery: Int = 1, startAt: Long = 0L): Long {
        val frames = (rate * seconds).toInt()
        var now = startAt
        for (i in 0..frames) {
            now = startAt + (i * second / rate).toLong()
            if (i % keepEvery == 0) onFrame(rate, now)
        }
        return now
    }

    @Test
    fun framesPerSecondFollowTheCodec() {
        assertEquals(25.0, FrameLossMeter.framesPerSecond(OmriNative.CODEC_AAC, 48_000, sbr = true), 1e-9)
        assertEquals(50.0, FrameLossMeter.framesPerSecond(OmriNative.CODEC_AAC, 48_000, sbr = false), 1e-9)
        assertEquals(41.667, FrameLossMeter.framesPerSecond(OmriNative.CODEC_MP2, 48_000, sbr = false), 1e-3)
    }

    @Test
    fun noLossBeforeAFullWindow() {
        val meter = FrameLossMeter(windowNanos = 5 * second)
        val now = meter.feed(25.0, seconds = 3)
        assertNull(meter.lossAt(now))
    }

    @Test
    fun everyFrameArrivingIsNoLoss() {
        val meter = FrameLossMeter(windowNanos = 5 * second)
        val now = meter.feed(25.0, seconds = 6)
        assertEquals(0f, meter.lossAt(now)!!, 0.01f)
    }

    @Test
    fun halfTheFramesMissingIsHalfLost() {
        val meter = FrameLossMeter(windowNanos = 5 * second)
        val now = meter.feed(25.0, seconds = 6, keepEvery = 2)
        assertEquals(0.5f, meter.lossAt(now)!!, 0.03f)
    }

    @Test
    fun framesStoppingIsTotalLoss() {
        val meter = FrameLossMeter(windowNanos = 5 * second)
        val last = meter.feed(25.0, seconds = 6)
        assertEquals(1f, meter.lossAt(last + 6 * second)!!, 0f)
    }

    @Test
    fun resetForgetsTheLoss() {
        val meter = FrameLossMeter(windowNanos = 5 * second)
        val now = meter.feed(25.0, seconds = 6, keepEvery = 2)
        meter.reset()
        assertNull(meter.lossAt(now))
    }
}
