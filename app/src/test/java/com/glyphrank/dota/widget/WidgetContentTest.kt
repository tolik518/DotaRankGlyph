package com.glyphrank.dota.widget

import com.glyphrank.dota.data.FakeSharedPreferences
import com.glyphrank.dota.data.PlayerRank
import com.glyphrank.dota.data.RankStore
import com.glyphrank.dota.glyph.RankRenderer
import com.glyphrank.dota.rank.Medal
import com.glyphrank.dota.rank.RankState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetContentTest {
    private val store = RankStore(FakeSharedPreferences())
    private val renderer = RankRenderer()
    private fun content() = WidgetContent.of(store, medals = null)

    @Test fun `without an ID it asks for one`() {
        val c = content()
        assertEquals("Dota Rank", c.name)
        assertEquals("Set your ID in the app", c.rank)
        assertArrayEquals(renderer.message("ID"), c.frame)
        assertNull(c.fetchedAtMs)
    }

    @Test fun `an ID that was never checked`() {
        store.accountId = 40453096
        val c = content()
        assertEquals("Player 40453096", c.name)
        assertEquals("Not checked yet", c.rank)
        assertArrayEquals(renderer.loading(0), c.frame)
        assertNull(c.fetchedAtMs)
    }

    @Test fun `a checked account shows its medal, name, rank and fetch time`() {
        store.accountId = 1199208054
        store.save(PlayerRank(1199208054, "Player 1", 54, null), nowMs = 1_000)
        val c = content()
        assertEquals("Player 1", c.name)
        assertEquals("Legend 4", c.rank)
        assertArrayEquals(renderer.render(RankState.Ranked(Medal.LEGEND, 4), null), c.frame)
        assertEquals(1_000L, c.fetchedAtMs)
    }

    @Test fun `a player without a name is shown by ID`() {
        store.accountId = 5
        store.save(PlayerRank(5, null, null, null), nowMs = 1_000)
        assertEquals("Player 5", content().name)
        assertEquals("Uncalibrated", content().rank)
    }

    @Test fun `the immortal place follows the display setting`() {
        store.accountId = 116233682
        store.save(PlayerRank(116233682, "ZQuixotix", 80, 2488), nowMs = 1_000)
        assertArrayEquals(renderer.render(RankState.Immortal(2488), null, showImmortalRank = true), content().frame)
        store.showImmortalRank = false
        assertArrayEquals(renderer.render(RankState.Immortal(2488), null, showImmortalRank = false), content().frame)
    }
}
