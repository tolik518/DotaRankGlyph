package com.glyphrank.dota.widget

import com.glyphrank.dota.data.FakeSharedPreferences
import com.glyphrank.dota.data.MockHttpServer
import com.glyphrank.dota.data.MockHttpServer.Response
import com.glyphrank.dota.data.OpenDotaClient
import com.glyphrank.dota.data.RankRepository
import com.glyphrank.dota.data.RankStore
import com.glyphrank.dota.util.FakeScheduler
import com.glyphrank.dota.util.MainThread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executor

/** A job run of [RankRefreshJob]: it must end exactly once, when the request is answered. */
class JobFetchTest {
    private val server = MockHttpServer()
    private val main = FakeScheduler()
    private val ioQueue = ArrayDeque<Runnable>()
    private var now = 1_790_344_800_000L
    private var networkNow = true
    private val store = RankStore(FakeSharedPreferences())
    private val repo = RankRepository(
        store = store,
        client = OpenDotaClient(baseUrl = "${server.baseUrl}/api", connectTimeoutMs = 2_000, readTimeoutMs = 2_000),
        io = Executor { ioQueue += it },
        main = main,
        clock = { now },
        medals = { null },
        applyLauncherIcon = { _, _ -> },
        canUseNetworkNow = { networkNow },
    )
    private var done = 0
    private val fetch = JobFetch(repo) { done++ }
    private val zq = 116233682L

    @Before fun setUp() {
        MainThread.delegate = main
        server.handler = { Response(200, """{"profile":{"account_id":$zq,"personaname":"ZQuixotix"},"rank_tier":80}""") }
    }

    @After fun tearDown() {
        main.advanceBy(60_000)
        MainThread.delegate = null
        server.close()
    }

    private fun finishRequests() {
        while (ioQueue.isNotEmpty()) ioQueue.removeFirst().run()
        main.runDue()
    }

    @Test fun `nothing to fetch ends the job at once`() {
        assertFalse(fetch.start()) // no account
        store.accountId = zq
        store.save(com.glyphrank.dota.data.PlayerRank(zq, "ZQuixotix", 80, null), nowMs = now)
        assertFalse(JobFetch(repo) { done++ }.start()) // fresh rank
        assertEquals(0, done)
        assertTrue(ioQueue.isEmpty())
    }

    @Test fun `a due refresh ends the job once it is answered`() {
        store.accountId = zq
        assertTrue(fetch.start())
        assertEquals(0, done)
        finishRequests()
        assertEquals(1, done)
        assertEquals(80, store.cachedForCurrentAccount()?.player?.rankTier)
        now += 10_000
        repo.refresh(manual = true) // later changes don't end it again
        finishRequests()
        assertEquals(1, done)
    }

    @Test fun `a request queued without network is sent by the job`() {
        networkNow = false
        repo.setAccount(zq)
        repo.refresh(manual = true) // e.g. a long-press with the app in the background
        assertTrue(ioQueue.isEmpty())
        assertTrue(fetch.start())
        finishRequests()
        assertEquals(1, server.requests.size)
        assertEquals(1, done)
    }

    @Test fun `a failed request ends the job too`() {
        server.handler = { Response(503, "", contentType = "text/html") }
        store.accountId = zq
        assertTrue(fetch.start())
        finishRequests()
        assertEquals(1, done)
    }

    @Test fun `a stopped job isn't finished later`() {
        store.accountId = zq
        assertTrue(fetch.start())
        fetch.cancel()
        finishRequests()
        assertEquals(0, done)
        assertEquals(80, store.cachedForCurrentAccount()?.player?.rankTier) // the answer is still saved
    }
}
