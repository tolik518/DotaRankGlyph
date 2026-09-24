package com.glyphrank.dota.rank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class PlayerInputTest {
    private val zeitboy = PlayerInput.Account(40453096)

    @Test fun `plain friend id`() = assertEquals(zeitboy, PlayerInput.parse(" 40453096 "))

    @Test fun `steamid64 is converted to account id`() =
        assertEquals(zeitboy, PlayerInput.parse("76561198000718824"))

    @Test fun `steam2 and steam3 formats`() {
        assertEquals(zeitboy, PlayerInput.parse("STEAM_0:0:20226548"))
        assertEquals(zeitboy, PlayerInput.parse("STEAM_1:0:20226548"))
        assertEquals(zeitboy, PlayerInput.parse("[U:1:40453096]"))
    }

    @Test fun `profile and stats site urls`() {
        assertEquals(zeitboy, PlayerInput.parse("https://steamcommunity.com/profiles/76561198000718824/"))
        assertEquals(zeitboy, PlayerInput.parse("https://www.opendota.com/players/40453096"))
        assertEquals(zeitboy, PlayerInput.parse("https://www.dotabuff.com/players/40453096/matches"))
        assertEquals(zeitboy, PlayerInput.parse("stratz.com/players/40453096"))
    }

    @Test fun `custom steam url is recognised for later resolving`() =
        assertEquals(PlayerInput.SteamVanity("Zeitboy"), PlayerInput.parse("https://steamcommunity.com/id/Zeitboy/"))

    @Test fun `steam ids and profile urls from real OpenDota responses`() {
        // Captured responses in src/test/resources/opendota/: every profile's steamid and
        // /profiles/ url must lead back to its account_id; /id/ urls are custom names.
        val fixtures = listOf(116233682L, 1199208054L, 1510911485L, 1747489664L, 1145501116L, 105013326L)
        for (id in fixtures) {
            val profile = JSONObject(javaClass.getResource("/opendota/player_$id.json")!!.readText())
                .getJSONObject("profile")
            assertEquals(id, profile.getLong("account_id"))
            assertEquals("$id", PlayerInput.Account(id), PlayerInput.parse(profile.getString("steamid")))
            val url = profile.getString("profileurl")
            val expected = if ("/id/" in url) PlayerInput.SteamVanity(url.trimEnd('/').substringAfterLast('/'))
            else PlayerInput.Account(id)
            assertEquals(url, expected, PlayerInput.parse(url))
        }
        assertEquals(PlayerInput.SteamVanity("player4"), PlayerInput.parse("https://steamcommunity.com/id/player4/"))
    }

    @Test fun `steam friend-code links are decoded offline`() {
        // Codes checked against Steam: steamcommunity.com/user/djn-gfvm redirects to /id/Zeitboy,
        // /user/djnh-kcm to /profiles/76561198000723736 (account 40458008).
        assertEquals(zeitboy, PlayerInput.parse("https://s.team/p/djn-gfvm"))
        assertEquals(zeitboy, PlayerInput.parse("https://s.team/p/djn-gfvm/QWERTYUI")) // quick invite with token
        assertEquals(zeitboy, PlayerInput.parse("steamcommunity.com/user/djn-gfvm/"))
        assertEquals(zeitboy, PlayerInput.parse("https://s.team/p/DJNGFVM")) // case and dash don't matter
        assertEquals(PlayerInput.Account(40458008), PlayerInput.parse("https://steamcommunity.com/user/djnh-kcm"))
        assertTrue(PlayerInput.parse("https://s.team/p/abc-xyz") is PlayerInput.Invalid) // a, x, y aren't code letters
    }

    @Test fun `garbage is rejected`() {
        for (bad in listOf("", "   ", "abc", "0", "5000000000", "12ab34", "99999999999999999999999")) {
            assertTrue("expected Invalid for '$bad'", PlayerInput.parse(bad) is PlayerInput.Invalid)
        }
    }
}
