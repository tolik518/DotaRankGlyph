package com.glyphrank.dota.data

/**
 * What the app remembers about its OpenDota requests, to stay inside the free tier
 * (60 requests/minute, 3000/day, counted per IP; rejected requests count too).
 * Persisted by [RankStore], so it survives restarts and Glyph reconnects.
 */
data class GuardState(
    /** No requests at all before this (wall clock); set after a 429 or when a quota is used up. */
    val blockedUntilMs: Long = 0,
    /** The block lasts until OpenDota's daily reset (00:00 UTC). */
    val blockedDaily: Boolean = false,
    /** Automatic refreshes wait until this: only a few requests are left for today. */
    val pausedUntilMs: Long = 0,
    /** Failed requests in a row (network, server errors, private profile …), for the backoff. */
    val failures: Int = 0,
    val lastAttemptMs: Long = 0,
    val lastAttemptAccount: Long = 0,
)

/**
 * Decides whether a refresh may send a request, and updates [GuardState] from the outcome.
 * Pure functions with the clock passed in, so every rule is unit-tested.
 *
 *  - A 429 or an exhausted quota blocks every request (manual ones too) until OpenDota's
 *    counter resets: the next UTC minute, or 00:00 UTC for the daily limit.
 *  - With fewer than [LOW_REMAINING_DAY] requests left today, automatic refreshes pause
 *    until 00:00 UTC; manual checks still go through.
 *  - After failures, automatic refreshes back off 1, 2, 4, 8 … minutes, never longer than
 *    the refresh interval.
 *  - At most one request per account every [MIN_GAP_MS], manual or not.
 */
object RefreshPolicy {
    sealed interface Decision {
        /** Send a request. */
        data object Fetch : Decision
        /** A request for this account is already running; the caller joins it. */
        data object Joined : Decision
        data object NoAccount : Decision
        /** The cached rank is newer than the refresh interval. */
        data object Fresh : Decision
        data class TooSoon(val untilMs: Long) : Decision
        data class Blocked(val untilMs: Long, val daily: Boolean) : Decision
        data class Paused(val untilMs: Long) : Decision
        data class BackingOff(val untilMs: Long) : Decision
    }

    const val MIN_GAP_MS = 5_000L
    const val LOW_REMAINING_DAY = 50
    /** Waits a little past OpenDota's reset, in case the phone's clock is slightly ahead. */
    const val RESET_MARGIN_MS = 5_000L
    private const val MINUTE_MS = 60_000L
    private const val DAY_MS = 24 * 60 * MINUTE_MS

    fun decide(
        accountId: Long,
        manual: Boolean,
        cachedFetchedAtMs: Long?,
        intervalMinutes: Int,
        guard: GuardState,
        nowMs: Long,
    ): Decision {
        if (active(guard.blockedUntilMs, nowMs)) return Decision.Blocked(guard.blockedUntilMs, guard.blockedDaily)
        val sinceLast = nowMs - guard.lastAttemptMs
        if (guard.lastAttemptAccount == accountId && sinceLast in 0 until MIN_GAP_MS) {
            return Decision.TooSoon(guard.lastAttemptMs + MIN_GAP_MS)
        }
        if (manual) return Decision.Fetch
        if (cachedFetchedAtMs != null && !RefreshInterval.isStale(cachedFetchedAtMs, nowMs, intervalMinutes)) {
            return Decision.Fresh
        }
        if (active(guard.pausedUntilMs, nowMs)) return Decision.Paused(guard.pausedUntilMs)
        if (guard.failures > 0) {
            val retryAt = guard.lastAttemptMs + backoffMs(guard.failures, intervalMinutes)
            if (sinceLast >= 0 && nowMs < retryAt) return Decision.BackingOff(retryAt)
        }
        return Decision.Fetch
    }

    fun attempt(guard: GuardState, accountId: Long, nowMs: Long) =
        guard.copy(lastAttemptMs = nowMs, lastAttemptAccount = accountId)

    fun afterSuccess(guard: GuardState, fetch: PlayerFetch, nowMs: Long): GuardState {
        val day = fetch.remainingDay
        val minute = fetch.remainingMinute
        return when {
            day != null && day <= 0 -> guard.blocked(nextUtcMidnight(nowMs), daily = true)
            minute != null && minute <= 0 -> guard.blocked(nextUtcMinute(nowMs), daily = false)
            else -> guard.copy(blockedUntilMs = 0, blockedDaily = false)
        }.copy(
            failures = 0,
            pausedUntilMs = if (day != null && day < LOW_REMAINING_DAY) nextUtcMidnight(nowMs) else 0,
        )
    }

    fun afterFailure(guard: GuardState, error: Throwable, nowMs: Long): GuardState {
        if (error is OpenDotaException && error.kind == OpenDotaException.Kind.RATE_LIMITED) {
            return if (error.limit == OpenDotaException.RateLimit.DAILY) {
                guard.blocked(nextUtcMidnight(nowMs), daily = true)
            } else {
                guard.blocked(nextUtcMinute(nowMs), daily = false)
            }
        }
        return guard.copy(failures = (guard.failures + 1).coerceAtMost(MAX_FAILURES))
    }

    /** 1, 2, 4, 8 … minutes, capped at the refresh interval. */
    fun backoffMs(failures: Int, intervalMinutes: Int): Long {
        if (failures <= 0) return 0
        val minutes = 1L shl (failures - 1).coerceAtMost(20)
        return minOf(minutes * MINUTE_MS, RefreshInterval.toMillis(intervalMinutes))
    }

    fun nextUtcMinute(nowMs: Long) = (nowMs / MINUTE_MS + 1) * MINUTE_MS + RESET_MARGIN_MS

    fun nextUtcMidnight(nowMs: Long) = (nowMs / DAY_MS + 1) * DAY_MS + RESET_MARGIN_MS

    private fun GuardState.blocked(untilMs: Long, daily: Boolean) =
        copy(blockedUntilMs = untilMs, blockedDaily = daily)

    /** A wait that ends in the future, but not absurdly far (the clock may have been turned back). */
    private fun active(untilMs: Long, nowMs: Long) = untilMs > nowMs && untilMs - nowMs <= DAY_MS + MINUTE_MS

    private const val MAX_FAILURES = 30
}
