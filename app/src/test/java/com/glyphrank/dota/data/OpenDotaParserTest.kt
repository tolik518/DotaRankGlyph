package com.glyphrank.dota.data

import com.glyphrank.dota.rank.Medal
import com.glyphrank.dota.rank.RankState
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class OpenDotaParserTest {
    // Real response for /api/players/40453096 (captured 2026-09-25, trimmed).
    private val zeitboy = """
        {"profile":{"account_id":40453096,"personaname":"Zeitboy | Z31780Y","name":null,"plus":true,
         "steamid":"76561198000718824","loccountrycode":"DE","is_subscriber":true},
         "rank_tier":24,"leaderboard_rank":null,"computed_mmr":3891}
    """.trimIndent()

    @Test fun `parses real response`() {
        val p = OpenDotaParser.parsePlayer(40453096, zeitboy)
        assertEquals(PlayerRank(40453096, "Zeitboy | Z31780Y", 24, null), p)
        assertEquals(RankState.Ranked(Medal.GUARDIAN, 4), p.state)
    }

    @Test fun `null rank_tier means uncalibrated`() {
        val p = OpenDotaParser.parsePlayer(1, """{"profile":{"personaname":"x"},"rank_tier":null}""")
        assertEquals(null, p.rankTier)
        assertEquals(RankState.Uncalibrated, p.state)
    }

    @Test fun `immortal with leaderboard`() {
        val p = OpenDotaParser.parsePlayer(1, """{"profile":{"personaname":"x"},"rank_tier":80,"leaderboard_rank":7}""")
        assertEquals(RankState.Immortal(7), p.state)
    }

    @Test fun `missing profile is not found`() = expectKind(OpenDotaException.Kind.NOT_FOUND, """{"profile":null}""")

    @Test fun `empty object is not found`() = expectKind(OpenDotaException.Kind.NOT_FOUND, "{}")

    @Test fun `non json is a parse error`() = expectKind(OpenDotaException.Kind.PARSE, "<html>502</html>")

    private fun expectKind(kind: OpenDotaException.Kind, body: String) {
        try {
            OpenDotaParser.parsePlayer(1, body)
            fail("expected $kind")
        } catch (e: OpenDotaException) {
            assertEquals(kind, e.kind)
        }
    }
}
