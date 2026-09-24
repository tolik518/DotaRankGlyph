package com.glyphrank.dota.rank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun `garbage is rejected`() {
        for (bad in listOf("", "   ", "abc", "0", "5000000000", "12ab34", "99999999999999999999999")) {
            assertTrue("expected Invalid for '$bad'", PlayerInput.parse(bad) is PlayerInput.Invalid)
        }
    }
}
