package com.glyphrank.dota.rank

import org.junit.Assert.assertEquals
import org.junit.Test

class RankTierTest {
    @Test fun `24 is Guardian with 4 stars`() =
        assertEquals(RankState.Ranked(Medal.GUARDIAN, 4), RankTier.decode(24))

    @Test fun `every medal from Herald to Divine decodes`() {
        assertEquals(RankState.Ranked(Medal.HERALD, 1), RankTier.decode(11))
        assertEquals(RankState.Ranked(Medal.CRUSADER, 3), RankTier.decode(33))
        assertEquals(RankState.Ranked(Medal.ARCHON, 2), RankTier.decode(42))
        assertEquals(RankState.Ranked(Medal.LEGEND, 5), RankTier.decode(55))
        assertEquals(RankState.Ranked(Medal.ANCIENT, 1), RankTier.decode(61))
        assertEquals(RankState.Ranked(Medal.DIVINE, 5), RankTier.decode(75))
    }

    @Test fun `legacy 7-star values are kept`() =
        assertEquals(RankState.Ranked(Medal.CRUSADER, 7), RankTier.decode(37))

    @Test fun `immortal carries leaderboard rank`() {
        assertEquals(RankState.Immortal(123), RankTier.decode(80, 123))
        assertEquals(RankState.Immortal(null), RankTier.decode(80, null))
        assertEquals(RankState.Immortal(null), RankTier.decode(80, 0))
    }

    @Test fun `missing or invalid rank_tier is uncalibrated`() {
        assertEquals(RankState.Uncalibrated, RankTier.decode(null))
        assertEquals(RankState.Uncalibrated, RankTier.decode(0))
        assertEquals(RankState.Uncalibrated, RankTier.decode(-5))
        assertEquals(RankState.Uncalibrated, RankTier.decode(95))
    }

    @Test fun `describe is human readable`() {
        assertEquals("Guardian 4", RankTier.describe(RankTier.decode(24)))
        assertEquals("Immortal #42", RankTier.describe(RankTier.decode(80, 42)))
        assertEquals("Uncalibrated", RankTier.describe(RankTier.decode(null)))
    }
}
