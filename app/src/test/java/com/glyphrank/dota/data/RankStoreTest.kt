package com.glyphrank.dota.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun `cache json round trip and broken json`() {
        val entries = mapOf(zq.accountId to RankCache.Entry(zq, 5), 2L to RankCache.Entry(PlayerRank(2, null, null, null), 6))
        assertEquals(entries, RankCache.decode(RankCache.encode(entries)))
        assertEquals(emptyMap<Long, RankCache.Entry>(), RankCache.decode("{oops"))
    }
}
