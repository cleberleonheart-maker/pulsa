package com.pulsa.player.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjLearnTest {

    @Test
    fun skipProne_needs_at_least_three_skips() {
        assertFalse(DjLearn.skipProne(skips = 2, plays = 0))
        assertTrue(DjLearn.skipProne(skips = 3, plays = 0))
    }

    @Test
    fun skipProne_requires_skips_to_keep_up_with_plays() {
        assertTrue(DjLearn.skipProne(skips = 3, plays = 3))
        assertTrue(DjLearn.skipProne(skips = 5, plays = 2))
        assertFalse(DjLearn.skipProne(skips = 3, plays = 4))
    }

    @Test
    fun momentWeight_same_day_and_hour_wins() {
        // Mesmo dia e mesma hora: 1 (dia) + 2 (hora) = 3.
        assertEquals(3, DjLearn.momentWeight(playDow = 3, playHour = 22, nowDow = 3, nowHour = 22))
        // Mesmo dia, hora vizinha: 1 + 1.
        assertEquals(2, DjLearn.momentWeight(playDow = 3, playHour = 21, nowDow = 3, nowHour = 22))
        // Dia diferente, mesma hora: só a hora conta.
        assertEquals(2, DjLearn.momentWeight(playDow = 5, playHour = 8, nowDow = 3, nowHour = 8))
        // Vizinhança com volta do relógio (23h ↔ 0h).
        assertEquals(1, DjLearn.momentWeight(playDow = 5, playHour = 0, nowDow = 3, nowHour = 23))
        // Nada em comum.
        assertEquals(0, DjLearn.momentWeight(playDow = 5, playHour = 9, nowDow = 3, nowHour = 22))
    }
}
