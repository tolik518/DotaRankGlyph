package com.glyphrank.dota.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RankStoreTest {
    private val prefs = FakeSharedPreferences()
    private val store = RankStore(prefs)
    private val zeitboy = PlayerRank(40453096, "Zeitboy | Z31780Y", 24, null)
    private val zq = PlayerRank(116233682, "ZQuixotix", 80, 2488)

    @Test fun `every account keeps its own cached rank across switches`() {
        store.accountId = zeitboy.accountId
        store.save(zeitboy, nowMs = 1_000)
        store.recentAccounts = RecentAccounts.add(store.recentAccounts, RecentAccounts.Entry(zeitboy.accountId, zeitboy.personaName))
        store.accountId = zq.accountId
        assertNull(store.cachedForCurrentAccount())
        store.save(zq, nowMs = 2_000)
        store.accountId = zeitboy.accountId
        assertEquals(RankStore.Cached(zeitboy, 1_000), store.cachedForCurrentAccount())
        assertEquals(RankStore.Cached(zq, 2_000), store.cachedFor(zq.accountId))
    }

    @Test fun `only the current and recent accounts stay cached`() {
        for (id in 1L..8L) {
            store.accountId = id
            store.save(PlayerRank(id, "P$id", 11, null), nowMs = id)
            store.recentAccounts = RecentAccounts.add(store.recentAccounts, RecentAccounts.Entry(id, "P$id"))
        }
        val cached = (1L..8L).filter { store.cachedFor(it) != null }
        assertEquals(listOf(3L, 4, 5, 6, 7, 8), cached) // the 5 recent ones before the last save, plus the current
    }

    @Test fun `a success clears the last error`() {
        store.accountId = zeitboy.accountId
        store.saveError(zeitboy.accountId, "Could not reach OpenDota", nowMs = 500)
        assertEquals("Could not reach OpenDota", store.lastErrorForCurrentAccount()?.message)
        store.save(zeitboy, nowMs = 1_000)
        assertNull(store.lastError)
    }

    @Test fun `the cache from before 0_2 is migrated`() {
        prefs.values.putAll(
            mapOf(
                "account_id" to 40453096L, "cached_account_id" to 40453096L, "persona_name" to "Zeitboy | Z31780Y",
                "rank_tier" to 24, "leaderboard_rank" to -1, "fetched_at" to 1_790_344_165_212L,
            ),
        )
        assertEquals(RankStore.Cached(zeitboy, 1_790_344_165_212L), store.cachedForCurrentAccount())
        assertEquals(setOf("account_id", "rank_cache"), prefs.values.keys)
        assertEquals(RankStore.Cached(zeitboy, 1_790_344_165_212L), RankStore(prefs).cachedForCurrentAccount())
    }

    @Test fun `settings have their defaults until changed`() {
        assertTrue(store.showImmortalRank)
        assertFalse(store.appIconShowsMedal)
        assertFalse(store.toyUsed)
        assertFalse(store.toyPromptDone)
        assertEquals(RefreshInterval.DEFAULT_MINUTES, store.refreshIntervalMinutes)
        store.showImmortalRank = false
        store.appIconShowsMedal = true
        store.toyUsed = true
        store.toyPromptDone = true
        store.refreshIntervalMinutes = 60
        val reopened = RankStore(prefs)
        assertFalse(reopened.showImmortalRank)
        assertTrue(reopened.appIconShowsMedal)
        assertTrue(reopened.toyUsed)
        assertTrue(reopened.toyPromptDone)
        assertEquals(60, reopened.refreshIntervalMinutes)
    }

    @Test fun `the refresh interval is kept within its limits`() {
        store.refreshIntervalMinutes = 1
        assertEquals(RefreshInterval.MIN_MINUTES, store.refreshIntervalMinutes)
        store.refreshIntervalMinutes = 7 * 24 * 60
        assertEquals(RefreshInterval.MAX_MINUTES, store.refreshIntervalMinutes)
        prefs.values[RankStore.KEY_REFRESH_INTERVAL] = 0 // e.g. written by an older version
        assertEquals(RefreshInterval.MIN_MINUTES, store.refreshIntervalMinutes)
    }

    @Test fun `a pending animation survives a restart and can be cleared`() {
        store.pendingCelebration = zq
        assertEquals(zq.copy(personaName = null), RankStore(prefs).pendingCelebration)
        store.pendingCelebration = PlayerRank(zeitboy.accountId, null, null, null) // was uncalibrated
        assertEquals(PlayerRank(zeitboy.accountId, null, null, null), store.pendingCelebration)
        store.pendingCelebration = null
        assertNull(store.pendingCelebration)
        assertTrue(prefs.values.keys.none { it.startsWith("pending_") })
    }

    @Test fun `errors of another account are not shown for the current one`() {
        store.accountId = zeitboy.accountId
        store.saveError(zq.accountId, "Player not found", nowMs = 500, kind = OpenDotaException.Kind.NOT_FOUND.name)
        assertNull(store.lastErrorForCurrentAccount())
        store.accountId = zq.accountId
        assertTrue(store.lastErrorForCurrentAccount()!!.isNotFound)
    }

    @Test fun `only bookkeeping keys are ignored by the glyph`() {
        store.accountId = zeitboy.accountId
        store.save(zeitboy, nowMs = 1)
        store.saveError(zeitboy.accountId, "x", nowMs = 2, kind = "HTTP")
        store.recentAccounts = listOf(RecentAccounts.Entry(zeitboy.accountId, null))
        store.guard = GuardState(blockedUntilMs = 1, blockedDaily = true, pausedUntilMs = 1, failures = 1, lastAttemptMs = 1, lastAttemptAccount = 1)
        store.toyUsed = true
        store.toyPromptDone = true
        store.pendingCelebration = zq
        store.appIconShowsMedal = true
        store.showImmortalRank = false
        store.refreshIntervalMinutes = 60

        val shown = setOf(
            RankStore.KEY_ACCOUNT_ID, RankStore.KEY_RANK_CACHE, RankStore.KEY_SHOW_IMMORTAL_RANK, RankStore.KEY_REFRESH_INTERVAL,
        )
        // Every key the store writes is either one of these or bookkeeping, so a new key must be sorted in here.
        for (key in prefs.values.keys) assertEquals(key, key !in shown, RankStore.isBookkeeping(key))
        assertFalse(RankStore.isBookkeeping(null)) // "cleared all": redraw
    }

    @Test fun `cache json round trip and broken json`() {
        val entries = mapOf(zq.accountId to RankCache.Entry(zq, 5), 2L to RankCache.Entry(PlayerRank(2, null, null, null), 6))
        assertEquals(entries, RankCache.decode(RankCache.encode(entries)))
        assertEquals(emptyMap<Long, RankCache.Entry>(), RankCache.decode("{oops"))
        // entries without a usable account ID or fetch time are dropped, the rest kept
        val mixed = """{"0":{"at":5,"tier":11},"x":{"at":5},"7":{"at":0,"tier":11},"8":{"tier":11},"9":{"at":5,"tier":11}}"""
        assertEquals(setOf(9L), RankCache.decode(mixed).keys)
    }
}
