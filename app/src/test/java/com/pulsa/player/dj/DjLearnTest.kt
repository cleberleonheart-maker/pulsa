package com.pulsa.player.dj

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
}
