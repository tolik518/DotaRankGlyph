package com.glyphrank.dota.data

import com.glyphrank.dota.data.MockHttpServer.Response
import com.glyphrank.dota.data.RefreshPolicy.Decision
import com.glyphrank.dota.glyph.RankCelebration
import com.glyphrank.dota.glyph.ReloadShake
import com.glyphrank.dota.rank.RankState
import com.glyphrank.dota.util.FakeScheduler
import com.glyphrank.dota.util.MainThread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executor

/**
 * [RankRepository] with the real [OpenDotaClient] against [MockHttpServer], an in-memory
 * store, a manual background executor and virtual main-thread time.
 */
class RankRepositoryTest {
    private val server = MockHttpServer()
    private val main = FakeScheduler()
    private val ioQueue = ArrayDeque<Runnable>()
    private val io = Executor { ioQueue += it }
    private var now = 1_790_344_800_000L // 2026-09-25 14:00 UTC
    private val launcherIcons = mutableListOf<Pair<RankState?, Boolean>>()
    private var widgetUpdates = 0
    private val store = RankStore(FakeSharedPreferences())
    private val repo = RankRepository(
        store = store,
        client = OpenDotaClient(baseUrl = "${server.baseUrl}/api", timeoutMs = 2_000),
        io = io,
        main = main,
        clock = { now },
        medals = { null },
        applyLauncherIcon = { state, show -> launcherIcons += state to show },
        updateWidgets = { widgetUpdates++ },
    )

    private val zeitboy = 40453096L
    private var zeitboyTier = 24

    /** Rank-state changes and saves the listeners heard about. */
    private val saved = mutableListOf<Pair<PlayerRank?, PlayerRank>>()
    private var stateChanges = 0
    private val celebrationFrames = mutableListOf<IntArray>()
    private val celebration = object : RankCelebration.Listener {
        override fun onCelebrationFrame(frame: IntArray) {
            celebrationFrames += frame
        }

        override fun onCelebrationEnd() = Unit
    }

    @Before fun setUp() {
        MainThread.delegate = main
        server.handler = { req ->
            val id = req.path.substringAfterLast('/').toLong()
            if (id == zeitboy) {
                Response(200, """{"profile":{"account_id":$id,"personaname":"Zeitboy"},"rank_tier":$zeitboyTier}""")
            } else {
                Response(200, fixture(id))
            }
        }
        repo.addListener(object : RankRepository.Listener {
            override fun onRankSaved(previous: PlayerRank?, current: PlayerRank) {
                saved += previous to current
            }

            override fun onStateChanged() {
                stateChanges++
            }
        })
    }

    @After fun tearDown() {
        main.advanceBy(60_000) // let shakes and animations finish; they are process-wide objects
        RankCelebration.removeListener(celebration)
        MainThread.delegate = null
        server.close()
    }

    /** Runs the queued background requests, then the main-thread work they posted. */
    private fun finishRequests() {
        while (ioQueue.isNotEmpty()) ioQueue.removeFirst().run()
        main.runDue()
    }

    private fun check(accountId: Long = zeitboy, manual: Boolean = true): Decision {
        repo.setAccount(accountId)
        return repo.refresh(manual)
    }

    // --- requests -----------------------------------------------------------------------

    @Test fun `a check saves the rank, adds a recent account and tells listeners`() {
        assertEquals(Decision.Fetch, check())
        assertTrue(repo.isLoading)
        finishRequests()
        assertFalse(repo.isLoading)
        assertEquals(RankStore.Cached(PlayerRank(zeitboy, "Zeitboy", 24, null), now), store.cachedForCurrentAccount())
        assertEquals(listOf(zeitboy), store.recentAccounts.map { it.accountId })
        assertEquals(listOf(null to PlayerRank(zeitboy, "Zeitboy", 24, null)), saved)
        assertTrue(stateChanges >= 2) // loading started, loading finished
    }

    @Test fun `a second caller joins the running request`() {
        assertEquals(Decision.Fetch, check())
        assertEquals(Decision.Joined, repo.refresh(manual = true))
        assertEquals(Decision.Joined, repo.refresh(manual = false))
        finishRequests()
        assertEquals(1, server.requests.size)
    }

    @Test fun `at most one request per account every 5 s`() {
        check()
        finishRequests()
        now += 2_000
        assertEquals(Decision.TooSoon(now + 3_000), repo.refresh(manual = true))
        now += 3_000
        assertEquals(Decision.Fetch, repo.refresh(manual = true))
        finishRequests()
        assertEquals(2, server.requests.size)
    }

    @Test fun `automatic refresh waits for the refresh interval`() {
        check()
        finishRequests()
        now += 10 * 60_000
        assertEquals(Decision.Fresh, repo.refresh(manual = false))
        now += store.refreshIntervalMinutes * 60_000L
        assertEquals(Decision.Fetch, repo.refresh(manual = false))
    }

    @Test fun `a 429 blocks even manual checks until the next utc minute`() {
        server.handler = { Response(429, """{"error":"minute rate limit exceeded"}""") }
        check()
        finishRequests()
        assertEquals("OpenDota rate limit hit, try again in a minute", store.lastErrorForCurrentAccount()?.message)
        now += 10_000
        val blocked = repo.refresh(manual = true)
        assertTrue("$blocked", blocked is Decision.Blocked)
        assertEquals(1, server.requests.size)
        now = (blocked as Decision.Blocked).untilMs
        assertEquals(Decision.Fetch, repo.refresh(manual = true))
    }

    @Test fun `errors are saved and cleared by the next success`() {
        server.handler = { Response(503, "<html>503</html>", contentType = "text/html") }
        check()
        finishRequests()
        assertEquals("OpenDota returned HTTP 503", store.lastErrorForCurrentAccount()?.message)
        assertNull(store.cachedForCurrentAccount())
        server.handler = { Response(200, """{"profile":{"account_id":$zeitboy,"personaname":"Zeitboy"},"rank_tier":24}""") }
        now += 10_000
        check()
        finishRequests()
        assertNull(store.lastError)
    }

    @Test fun `a private or unknown profile is saved as not found`() {
        server.handler = { Response(200, """{"profile":null,"rank_tier":null}""") }
        check()
        finishRequests()
        assertTrue(store.lastErrorForCurrentAccount()!!.isNotFound)
        server.handler = { Response(503, "<html>503</html>", contentType = "text/html") }
        now += 10_000
        repo.refresh(manual = true)
        finishRequests()
        assertFalse(store.lastErrorForCurrentAccount()!!.isNotFound)
    }

    @Test fun `a result for an account switched away mid-request is ignored`() {
        check(zeitboy)
        repo.setAccount(116233682)
        finishRequests()
        assertNull(store.cachedFor(zeitboy))
        assertNull(store.cachedForCurrentAccount())
        assertTrue(saved.isEmpty())
    }

    // --- shake, animation, icon ------------------------------------------------------------

    @Test fun `a manual check of a known rank shakes until the request is done`() {
        check()
        finishRequests()
        now += 10_000
        repo.refresh(manual = true)
        main.advanceBy(500)
        assertTrue(ReloadShake.isShaking)
        finishRequests()
        main.advanceBy(1_000)
        assertFalse(ReloadShake.isShaking)
    }

    @Test fun `the first rank of an account doesn't shake`() {
        check()
        assertFalse(ReloadShake.isShaking)
    }

    @Test fun `a changed rank animates, and is kept for the glyph if the toy isn't showing`() {
        RankCelebration.addListener(celebration)
        zeitboyTier = 23
        check()
        finishRequests()
        assertTrue(celebrationFrames.isEmpty()) // first rank: nothing to compare
        zeitboyTier = 24
        now += 10_000
        repo.refresh(manual = true)
        finishRequests()
        main.advanceBy(10_000)
        assertTrue(celebrationFrames.size > 5)
        assertEquals(PlayerRank(zeitboy, null, 23, null), store.pendingCelebration)
        assertEquals(PlayerRank(zeitboy, "Zeitboy", 23, null) to PlayerRank(zeitboy, "Zeitboy", 24, null), saved.last())
    }

    @Test fun `no pending animation when the toy shows it live`() {
        RankCelebration.addListener(celebration, glyph = true)
        zeitboyTier = 23
        check()
        finishRequests()
        zeitboyTier = 24
        now += 10_000
        repo.refresh(manual = true)
        finishRequests()
        main.advanceBy(10_000)
        assertTrue(celebrationFrames.isNotEmpty())
        assertNull(store.pendingCelebration)
    }

    @Test fun `an unchanged rank doesn't animate`() {
        RankCelebration.addListener(celebration)
        check()
        finishRequests()
        now += 10_000
        repo.refresh(manual = true)
        finishRequests()
        main.advanceBy(10_000)
        assertTrue(celebrationFrames.isEmpty())
        assertNull(store.pendingCelebration)
    }

    @Test fun `widgets are redrawn after a switch and after every result`() {
        check()
        assertEquals(1, widgetUpdates) // switched to the account
        finishRequests()
        assertEquals(2, widgetUpdates) // rank saved
        server.handler = { Response(503, "", contentType = "text/html") }
        now += 10_000
        repo.refresh(manual = true)
        finishRequests()
        assertEquals(3, widgetUpdates) // error saved (the widget keeps the last rank)
    }

    @Test fun `the launcher icon follows the medal only with the setting on`() {
        check()
        finishRequests()
        assertTrue(launcherIcons.isEmpty())
        store.appIconShowsMedal = true
        now += 10_000
        repo.refresh(manual = true)
        finishRequests()
        assertEquals(RankState.Ranked(com.glyphrank.dota.rank.Medal.GUARDIAN, 4) to true, launcherIcons.last())
    }

    private fun fixture(accountId: Long): String =
        javaClass.getResource("/opendota/player_$accountId.json")!!.readText(Charsets.UTF_8)
}
