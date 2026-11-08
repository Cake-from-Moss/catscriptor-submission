package org.cfm.catscriptor.viewmodel.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformHistoryTest {
    @Test
    fun `starts flat`() {
        assertEquals(List(20) { 0f }, WaveformHistory().snapshot())
    }

    @Test
    fun `newest bar is on the right and older bars shift left`() {
        val history = WaveformHistory(barCount = 3)
        history.push(-60f)
        history.push(-30f)
        history.push(0f)
        assertEquals(listOf(0f, 0.5f, 1f), history.snapshot())

        history.push(-45f)
        assertEquals(listOf(0.5f, 1f, 0.25f), history.snapshot())
    }

    @Test
    fun `height is proportional to level and clamped`() {
        assertEquals(0f, levelFraction(-90f), 0f)
        assertEquals(0f, levelFraction(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(0f, levelFraction(Float.NaN), 0f)
        assertEquals(0.5f, levelFraction(-30f), 1e-6f)
        assertEquals(1f, levelFraction(3f), 0f)
    }

    @Test
    fun `clear resets bars`() {
        val history = WaveformHistory(barCount = 2)
        history.push(0f)
        history.clear()
        assertEquals(listOf(0f, 0f), history.snapshot())
    }
}

class LevelMeterTest {
    private fun speech(meter: LevelMeter, db: Float, at: Long, peak: Float = db + 6f) =
        meter.update(levelDbfs = db, peakDbfs = peak, speechDetected = true, nowMs = at)

    @Test
    fun `maps -60 to 0 dBFS linearly onto 16 segments`() {
        assertEquals(0, LevelMeter().update(-60f, -60f, true, 0).litSegments)
        assertEquals(8, LevelMeter().update(-30f, -30f, true, 0).litSegments)
        assertEquals(16, LevelMeter().update(0f, -10f, true, 0).litSegments)
    }

    @Test
    fun `rise is instant and decay is limited to 20 dB per second`() {
        val meter = LevelMeter()
        assertEquals(16, speech(meter, -0f, at = 0, peak = -10f).litSegments)
        // Через 100 мс уровень упал до тишины, но индикатор опустился только на 2 дБ.
        val after100 = speech(meter, -60f, at = 100)
        assertEquals(15, after100.litSegments) // −2 dBFS → 15.47 → 15
        // Через секунду от пика: −20 dBFS → 10.67 → 11 сегментов.
        val after1000 = speech(meter, -60f, at = 1_000)
        assertEquals(11, after1000.litSegments)
        // Новый громкий буфер поднимает индикатор сразу.
        assertEquals(16, speech(meter, 0f, at = 1_100, peak = -10f).litSegments)
    }

    @Test
    fun `too quiet only after 2 s of quiet speech`() {
        val meter = LevelMeter()
        assertEquals(LevelStatus.NORMAL, speech(meter, -50f, at = 0).status)
        assertEquals(LevelStatus.NORMAL, speech(meter, -50f, at = 1_900).status)
        assertEquals(LevelStatus.TOO_QUIET, speech(meter, -50f, at = 2_000).status)
        assertEquals(LevelStatus.NORMAL, speech(meter, -30f, at = 2_100).status)
    }

    @Test
    fun `silence only after 5 s without speech`() {
        val meter = LevelMeter()
        assertEquals(LevelStatus.NORMAL, meter.update(-58f, -55f, false, 0).status)
        assertEquals(LevelStatus.NORMAL, meter.update(-58f, -55f, false, 4_900).status)
        assertEquals(LevelStatus.SILENCE, meter.update(-58f, -55f, false, 5_000).status)
        assertEquals(LevelStatus.NORMAL, speech(meter, -25f, at = 5_100).status)
    }

    @Test
    fun `quiet level without speech is silence, not too quiet`() {
        val meter = LevelMeter()
        meter.update(-50f, -45f, false, 0)
        assertEquals(LevelStatus.SILENCE, meter.update(-50f, -45f, false, 6_000).status)
    }

    @Test
    fun `clipping wins and is held for a while`() {
        val meter = LevelMeter()
        val clipped = speech(meter, -6f, at = 0, peak = -1f)
        assertEquals(LevelStatus.CLIPPING, clipped.status)
        assertTrue(clipped.status.isWarning)
        assertEquals(LevelStatus.CLIPPING, speech(meter, -20f, at = 1_000).status)
        assertEquals(LevelStatus.NORMAL, speech(meter, -20f, at = 1_600).status)
    }

    @Test
    fun `peak exactly at threshold is not clipping`() {
        assertEquals(LevelStatus.NORMAL, LevelMeter().update(-10f, -3f, true, 0).status)
    }

    @Test
    fun `reset forgets timers so a pause does not count as silence`() {
        val meter = LevelMeter()
        meter.update(-58f, -55f, false, 0)
        meter.reset()
        assertEquals(LevelStatus.NORMAL, meter.update(-58f, -55f, false, 6_000).status)
        assertEquals(0, meter.update(-60f, -55f, false, 6_100).litSegments)
    }
}
