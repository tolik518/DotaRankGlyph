package com.glyphrank.dota.data

import com.glyphrank.dota.data.RefreshPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class RefreshPolicyTest {
    private val account = 40453096L
    private val interval = 30 // minutes

    /** 2026-09-25 14:03:20 UTC. */
    private val now = 1_790_344_800_000L + 3 * 60_000 + 20_000
    private val minute = 60_000L
    private val midnightUtc = 1_790_380_800_000L // 2026-09-26 00:00 UTC

    private fun decide(
        guard: GuardState = GuardState(),
        manual: Boolean = false,
        fetchedAt: Long? = now - 2 * interval * minute, // stale by default
        at: Long = now,
    ) = RefreshPolicy.decide(account, manual, fetchedAt, interval, guard, at)

    private fun fetch(minuteLeft: Int? = 59, dayLeft: Int? = 2900) =
        PlayerFetch(PlayerRank(account, "Zeitboy", 24, null), minuteLeft, dayLeft)

    private val rateLimited = OpenDotaException(OpenDotaException.Kind.RATE_LIMITED, "", limit = OpenDotaException.RateLimit.MINUTE)
    private val dailyLimited = OpenDotaException(OpenDotaException.Kind.RATE_LIMITED, "", limit = OpenDotaException.RateLimit.DAILY)
    private val offline = OpenDotaException(OpenDotaException.Kind.NETWORK, "", IOException())

    @Test fun `utc reset times`() {
        assertEquals(now - 20_000 + minute + RefreshPolicy.RESET_MARGIN_MS, RefreshPolicy.nextUtcMinute(now))
        assertEquals(midnightUtc + RefreshPolicy.RESET_MARGIN_MS, RefreshPolicy.nextUtcMidnight(now))
    }

    // --- normal refreshes -------------------------------------------------------------

    @Test fun `stale cache or no cache fetches, fresh cache does not`() {
        assertEquals(Decision.Fetch, decide())
        assertEquals(Decision.Fetch, decide(fetchedAt = null))
        assertEquals(Decision.Fresh, decide(fetchedAt = now - 10 * minute))
    }

    @Test fun `manual refresh ignores the interval`() =
        assertEquals(Decision.Fetch, decide(manual = true, fetchedAt = now - minute))

    @Test fun `at most one request per account every 5 s, manual too`() {
        val guard = RefreshPolicy.attempt(GuardState(), account, now - 2_000)
        assertEquals(Decision.TooSoon(now + 3_000), decide(guard, manual = true))
        assertEquals(Decision.Fetch, decide(guard, manual = true, at = now + 3_000))
        // another account isn't held back
        assertEquals(Decision.Fetch, RefreshPolicy.decide(1L, true, null, interval, guard, now))
    }

    @Test fun `the 5 s gap counts from the attempt itself`() {
        val guard = RefreshPolicy.attempt(GuardState(), account, now)
        assertEquals(Decision.TooSoon(now + 5_000), decide(guard, manual = true))
        assertEquals(Decision.TooSoon(now + 5_000), decide(guard, manual = true, at = now + 4_999))
    }

    @Test fun `an attempt in the future (clock turned back) doesn't hold checks back`() {
        val guard = RefreshPolicy.attempt(GuardState(), account, now + 60_000)
        assertEquals(Decision.Fetch, decide(guard, manual = true))
    }

    // --- 429 ---------------------------------------------------------------------------

    @Test fun `minute limit blocks everything until the next utc minute`() {
        val guard = RefreshPolicy.afterFailure(GuardState(), rateLimited, now)
        val until = RefreshPolicy.nextUtcMinute(now)
        assertEquals(Decision.Blocked(until, daily = false), decide(guard))
        assertEquals(Decision.Blocked(until, daily = false), decide(guard, manual = true))
        assertEquals(Decision.Fetch, decide(guard, at = until))
        assertEquals(0, guard.failures) // a 429 isn't a failure to back off from
    }

    @Test fun `daily limit blocks everything until 00_00 utc`() {
        val guard = RefreshPolicy.afterFailure(GuardState(), dailyLimited, now)
        val until = midnightUtc + RefreshPolicy.RESET_MARGIN_MS
        assertEquals(Decision.Blocked(until, daily = true), decide(guard, manual = true))
        assertEquals(Decision.Blocked(until, daily = true), decide(guard, at = midnightUtc))
        assertEquals(Decision.Fetch, decide(guard, at = until))
    }

    @Test fun `a success lifts the block`() {
        val blocked = RefreshPolicy.afterFailure(GuardState(), rateLimited, now)
        val ok = RefreshPolicy.afterSuccess(blocked, fetch(), now + minute)
        assertEquals(0L, ok.blockedUntilMs)
    }

    @Test fun `a block far in the future is ignored (clock turned back)`() {
        val guard = GuardState(blockedUntilMs = now + 3 * 24 * 3600_000L, blockedDaily = true)
        assertEquals(Decision.Fetch, decide(guard))
        val longest = now + 24 * 60 * minute + minute // a daily block, set just after midnight
        assertEquals(Decision.Blocked(longest, daily = true), decide(GuardState(blockedUntilMs = longest, blockedDaily = true)))
        assertEquals(Decision.Fetch, decide(GuardState(blockedUntilMs = longest + 1, blockedDaily = true)))
    }

    // --- remaining-request headers ----------------------------------------------------

    @Test fun `few requests left today pause automatic refreshes only`() {
        val guard = RefreshPolicy.afterSuccess(GuardState(), fetch(dayLeft = 20), now)
        val until = midnightUtc + RefreshPolicy.RESET_MARGIN_MS
        assertEquals(Decision.Paused(until), decide(guard, at = now + 2 * interval * minute))
        assertEquals(Decision.Fetch, decide(guard, manual = true, at = now + minute))
        assertEquals(Decision.Fetch, decide(guard, at = until))
        assertEquals(0L, RefreshPolicy.afterSuccess(guard, fetch(dayLeft = 2999), until).pausedUntilMs)
    }

    @Test fun `automatic refreshes pause below 50 requests left today`() {
        assertEquals(0L, RefreshPolicy.afterSuccess(GuardState(), fetch(dayLeft = 50), now).pausedUntilMs)
        assertEquals(midnightUtc + RefreshPolicy.RESET_MARGIN_MS, RefreshPolicy.afterSuccess(GuardState(), fetch(dayLeft = 49), now).pausedUntilMs)
    }

    @Test fun `used-up quota blocks before OpenDota has to say 429`() {
        val noneToday = RefreshPolicy.afterSuccess(GuardState(), fetch(dayLeft = 0), now)
        assertEquals(Decision.Blocked(midnightUtc + RefreshPolicy.RESET_MARGIN_MS, daily = true), decide(noneToday, manual = true))
        val noneThisMinute = RefreshPolicy.afterSuccess(GuardState(), fetch(minuteLeft = 0), now)
        assertEquals(Decision.Blocked(RefreshPolicy.nextUtcMinute(now), daily = false), decide(noneThisMinute, manual = true))
    }

    @Test fun `missing headers change nothing`() {
        val guard = RefreshPolicy.afterSuccess(GuardState(), fetch(minuteLeft = null, dayLeft = null), now)
        assertEquals(GuardState(), guard)
    }

    // --- backoff ------------------------------------------------------------------------

    @Test fun `backoff doubles from 1 min and is capped at the interval`() {
        assertEquals(listOf(0L, 1, 2, 4, 8, 16, 30, 30), (0..7).map { RefreshPolicy.backoffMs(it, interval) / minute })
        assertEquals(5L, RefreshPolicy.backoffMs(10, 5) / minute)
    }

    @Test fun `failures make automatic refreshes back off`() {
        var guard = GuardState()
        var at = now
        for (failures in 1..4) {
            guard = RefreshPolicy.afterFailure(RefreshPolicy.attempt(guard, account, at), offline, at)
            assertEquals(failures, guard.failures)
            val retryAt = at + RefreshPolicy.backoffMs(failures, interval)
            assertEquals(Decision.BackingOff(retryAt), decide(guard, fetchedAt = null, at = retryAt - 1))
            assertEquals(Decision.Fetch, decide(guard, fetchedAt = null, at = retryAt))
            at = retryAt
        }
        // manual checks don't wait, and a success resets the backoff
        assertEquals(Decision.Fetch, decide(guard, manual = true, at = at + 10_000))
        assertEquals(0, RefreshPolicy.afterSuccess(guard, fetch(), at).failures)
    }
}
