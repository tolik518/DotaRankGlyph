package com.glyphrank.dota.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeTextTest {
    private val now = 1_790_344_800_000L
    private val minute = 60_000L

    @Test fun `age texts`() {
        assertEquals("just now", TimeText.ago(now - 59_000, now))
        assertEquals("just now", TimeText.ago(now + 5 * minute, now)) // clock skew
        assertEquals("1 min ago", TimeText.ago(now - minute, now))
        assertEquals("59 min ago", TimeText.ago(now - 59 * minute, now))
        assertEquals("1 h ago", TimeText.ago(now - 60 * minute, now))
        assertEquals("47 h ago", TimeText.ago(now - 47 * 60 * minute, now))
        assertEquals("2 days ago", TimeText.ago(now - 48 * 60 * minute, now))
    }
}
