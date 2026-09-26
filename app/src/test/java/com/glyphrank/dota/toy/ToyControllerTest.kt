package com.glyphrank.dota.toy

import com.glyphrank.dota.data.FakeSharedPreferences
import com.glyphrank.dota.data.MockHttpServer
import com.glyphrank.dota.data.MockHttpServer.Response
import com.glyphrank.dota.data.OpenDotaClient
import com.glyphrank.dota.data.PlayerRank
import com.glyphrank.dota.data.RankRepository
import com.glyphrank.dota.data.RankStore
import com.glyphrank.dota.data.RefreshPolicy.Decision
import com.glyphrank.dota.glyph.RankCelebration
import com.glyphrank.dota.glyph.RankRenderer
import com.glyphrank.dota.glyph.ReloadShake
import com.glyphrank.dota.rank.RankState
import com.glyphrank.dota.util.FakeScheduler
import com.glyphrank.dota.util.MainThread
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executor

/** What the Glyph toy shows, with the real repository against [MockHttpServer] and virtual time. */
class ToyControllerTest {
    private val server = MockHttpServer()
    private val main = FakeScheduler()
    private val ioQueue = ArrayDeque<Runnable>()
    private var now = 1_790_344_800_000L // 2026-09-25 14:00 UTC
    private val store = RankStore(FakeSharedPreferences())
    private val repo = RankRepository(
        store = store,
        client = OpenDotaClient(baseUrl = "${server.baseUrl}/api", connectTimeoutMs = 2_000, readTimeoutMs = 2_000),
        io = Executor { ioQueue += it },
        main = main,
        clock = { now },
        medals = { null },
        applyLauncherIcon = { _, _ -> },
    )
    private val frames = mutableListOf<IntArray>()
    private val toy = ToyController(repo, medals = null, main = main, show = { frames += it })
    private val renderer = RankRenderer()

    private val zq = 116233682L
    private val immortal = PlayerRank(zq, "ZQuixotix", 80, 2488)
    private var answer = immortal

    @Before fun setUp() {
        MainThread.delegate = main
        server.handler = {
            Response(200, """{"profile":{"account_id":${answer.accountId},"personaname":"${answer.personaName}"},"rank_tier":${answer.rankTier},"leaderboard_rank":${answer.leaderboardRank}}""")
        }
    }

    @After fun tearDown() {
        toy.stop()
        main.advanceBy(60_000)
        MainThread.delegate = null
        server.close()
    }

    private fun finishRequests() {
        while (ioQueue.isNotEmpty()) ioQueue.removeFirst().run()
        main.runDue()
    }

    private fun cache(player: PlayerRank, ageMinutes: Int) {
        store.accountId = player.accountId
        store.save(player, nowMs = now - ageMinutes * 60_000L)
    }

    private fun medal(state: RankState, showImmortalRank: Boolean = true) = renderer.render(state, null, showImmortalRank)

    private fun assertShowing(expected: IntArray) = assertArrayEquals(expected, frames.last())

    /** Frames shown from index [from] on that are not [frame]: a shake or an animation. */
    private fun framesOtherThan(frame: IntArray, from: Int = 0) = frames.drop(from).count { !it.contentEquals(frame) }

    // --- selecting the toy ------------------------------------------------------------

    @Test fun `shows the cached medal at once and doesn't fetch a fresh one`() {
        cache(immortal, ageMinutes = 5)
        toy.start()
        assertShowing(medal(RankState.Immortal(2488)))
        assertTrue(server.requests.isEmpty())
        assertTrue(store.toyUsed) // the app stops suggesting the toy
    }

    @Test fun `a stale rank is refreshed in the background, without a spinner or shake`() {
        cache(immortal.copy(leaderboardRank = 2600), ageMinutes = 120)
        toy.start()
        main.advanceBy(500)
        assertEquals(0, framesOtherThan(medal(RankState.Immortal(2600)))) // no animation while loading
        finishRequests()
        main.advanceBy(10_000) // the rank change plays
        assertShowing(medal(RankState.Immortal(2488)))
        assertEquals(1, server.requests.size)
    }

    @Test fun `without an ID it asks for one`() {
        toy.start()
        assertShowing(renderer.message("ID"))
        assertTrue(server.requests.isEmpty())
    }

    @Test fun `the first rank of an account spins until it arrives`() {
        store.accountId = zq
        toy.start()
        main.advanceBy(3 * ToyController.SPINNER_FRAME_MS)
        assertShowing(renderer.loading(3))
        finishRequests()
        assertShowing(medal(RankState.Immortal(2488)))
        val shown = frames.size
        main.advanceBy(1_000)
        assertEquals(shown, frames.size) // the spinner stopped
    }

    @Test fun `an error keeps the last medal on the glyph`() {
        cache(immortal, ageMinutes = 120)
        server.handler = { Response(503, "<html>503</html>", contentType = "text/html") }
        toy.start()
        finishRequests()
        main.advanceBy(1_000)
        assertShowing(medal(RankState.Immortal(2488)))
        assertEquals("OpenDota returned HTTP 503", store.lastError?.message) // shown in the app instead
    }

    @Test fun `a first rank that fails shows the empty ring, not an error`() {
        store.accountId = zq
        server.handler = { Response(503, "<html>503</html>", contentType = "text/html") }
        toy.start()
        finishRequests()
        assertShowing(renderer.loading(0))
    }

    // --- long-press -----------------------------------------------------------------

    @Test fun `long-press checks now and shakes the medal until the answer is in`() {
        cache(immortal, ageMinutes = 5)
        toy.start()
        toy.onLongPress()
        main.advanceBy(500)
        assertTrue(framesOtherThan(medal(RankState.Immortal(2488))) > 2) // shaking
        finishRequests()
        main.advanceBy(10_000)
        assertShowing(medal(RankState.Immortal(2488)))
        assertFalse(ReloadShake.isShaking)
        assertEquals(1, server.requests.size)
    }

    @Test fun `long-press right after a check shakes once, without a request`() {
        cache(immortal, ageMinutes = 5)
        toy.start()
        toy.onLongPress()
        finishRequests()
        main.advanceBy(10_000)
        now += 1_000
        assertTrue(repo.refresh(manual = true) is Decision.TooSoon) // as the second long-press will be
        toy.onLongPress()
        assertTrue(ReloadShake.isShaking)
        main.advanceBy(10_000)
        assertEquals(1, server.requests.size)
        assertShowing(medal(RankState.Immortal(2488)))
    }

    @Test fun `long-press without an ID does nothing`() {
        toy.start()
        toy.onLongPress()
        assertFalse(ReloadShake.isShaking)
        assertTrue(server.requests.isEmpty())
    }

    // --- rank changes -----------------------------------------------------------------

    @Test fun `a change missed while away plays first, then the new medal stays`() {
        cache(immortal, ageMinutes = 5)
        store.pendingCelebration = immortal.copy(leaderboardRank = 2600)
        toy.start()
        main.advanceBy(100)
        assertTrue(frames.none { it.contentEquals(medal(RankState.Immortal(2488))) }) // the old rank first
        store.showImmortalRank = true // a setting write mid-animation doesn't cut it short
        assertTrue(frames.none { it.contentEquals(medal(RankState.Immortal(2488))) })
        main.advanceBy(10_000)
        assertTrue(frames.size > 5)
        assertShowing(medal(RankState.Immortal(2488)))
        assertNull(store.pendingCelebration)
    }

    // --- settings and ticks -------------------------------------------------------------

    @Test fun `display settings redraw at once, bookkeeping doesn't`() {
        cache(immortal, ageMinutes = 5)
        toy.start()
        val shown = frames.size
        store.toyPromptDone = true
        store.recentAccounts = emptyList()
        assertEquals(shown, frames.size)
        store.showImmortalRank = false
        assertShowing(medal(RankState.Immortal(2488), showImmortalRank = false))
    }

    @Test fun `a shorter refresh interval fetches a rank that is now stale`() {
        store.refreshIntervalMinutes = 60
        cache(immortal, ageMinutes = 40)
        toy.start()
        assertTrue(server.requests.isEmpty())
        store.refreshIntervalMinutes = 30
        assertEquals(1, server.requests.size + ioQueue.size) // queued for the background thread
    }

    @Test fun `the always-on tick redraws and fetches only when stale`() {
        cache(immortal, ageMinutes = 5)
        toy.start()
        val shown = frames.size
        toy.onAodTick()
        assertEquals(shown + 1, frames.size)
        assertTrue(ioQueue.isEmpty())
        now += 60 * 60_000L
        toy.onAodTick()
        assertEquals(1, ioQueue.size)
    }

    @Test fun `a check from the app shakes the glyph too`() {
        cache(immortal, ageMinutes = 5)
        toy.start()
        val shown = frames.size
        repo.refresh(manual = true) // "Save & check rank"
        main.advanceBy(500)
        assertTrue(framesOtherThan(medal(RankState.Immortal(2488)), from = shown) > 2)
    }

    @Test fun `a new rank arriving mid-shake shows only after the shake`() {
        val old = immortal.copy(leaderboardRank = 2600)
        cache(old, ageMinutes = 5)
        toy.start()
        toy.onLongPress()
        main.advanceBy(100)
        val pressed = frames.size
        finishRequests() // the new place is saved while the old medal still shakes
        while (ReloadShake.isShaking) main.advanceBy(RankRenderer.SHAKE_FRAME_MS)
        val shakes = (0 until RankRenderer.SHAKE_CYCLE_STEPS).map { renderer.shake(medal(RankState.Immortal(2600)), it) }
        val duringShake = frames.drop(pressed).dropLast(1)
        assertTrue(duringShake.isNotEmpty())
        assertTrue(duringShake.all { f -> shakes.any { it.contentEquals(f) } })
        main.advanceBy(10_000) // then the change animates
        assertShowing(medal(RankState.Immortal(2488)))
    }

    @Test fun `after stop nothing is drawn any more`() {
        cache(immortal, ageMinutes = 5)
        toy.start()
        toy.stop()
        val shown = frames.size
        main.advanceBy(1_000)
        store.showImmortalRank = false
        repo.refresh(manual = true)
        finishRequests()
        main.advanceBy(10_000)
        assertEquals(shown, frames.size)
        assertFalse(RankCelebration.glyphListening)
    }
}
