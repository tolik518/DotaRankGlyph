package com.glyphrank.dota.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshIntervalTest {
    @Test fun `interval is limited to 5 minutes - once a day`() {
        assertEquals(5, RefreshInterval.clamp(1))
        assertEquals(5, RefreshInterval.clamp(0))
        assertEquals(24 * 60, RefreshInterval.clamp(7 * 24 * 60))
        assertEquals(60, RefreshInterval.clamp(60))
    }

    @Test fun `every offered choice is allowed and both limits are offered`() {
        val choices = RefreshInterval.CHOICES_MINUTES
        assertTrue(choices.all { RefreshInterval.clamp(it) == it })
        assertEquals(5, choices.first())
        assertEquals(24 * 60, choices.last())
        assertTrue(RefreshInterval.DEFAULT_MINUTES in choices)
    }

    @Test fun `an aod tick a minute later does not fetch again`() {
        val fetchedAt = 1_000_000L
        assertFalse(RefreshInterval.isStale(fetchedAt, fetchedAt + 60_000, minutes = 5))
        assertFalse(RefreshInterval.isStale(fetchedAt, fetchedAt + 5 * 60_000, minutes = 5))
        assertTrue(RefreshInterval.isStale(fetchedAt, fetchedAt + 5 * 60_000 + 1, minutes = 5))
        assertFalse(RefreshInterval.isStale(fetchedAt, fetchedAt + 23 * 3_600_000L, minutes = 24 * 60))
        assertTrue(RefreshInterval.isStale(fetchedAt, fetchedAt + 25 * 3_600_000L, minutes = 24 * 60))
    }

    @Test fun `labels`() {
        assertEquals("Every 5 min", RefreshInterval.label(5))
        assertEquals("Every 3 h", RefreshInterval.label(180))
        assertEquals("Once a day", RefreshInterval.label(24 * 60))
    }
}
