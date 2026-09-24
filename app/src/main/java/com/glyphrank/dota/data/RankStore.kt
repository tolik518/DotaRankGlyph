package com.glyphrank.dota.data

import android.content.Context
import android.content.SharedPreferences

/** Shared between the settings screen and the toy service (same process). */
class RankStore(context: Context) {
    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var accountId: Long?
        get() = prefs.getLong(KEY_ACCOUNT_ID, 0L).takeIf { it > 0 }
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove(KEY_ACCOUNT_ID) else editor.putLong(KEY_ACCOUNT_ID, value)
            // A new account invalidates the cached rank.
            if (value != cached()?.player?.accountId) editor.remove(KEY_FETCHED_AT)
            editor.apply()
        }

    /** Show the Immortal leaderboard place (e.g. 2488) on the Immortal medal. On by default. */
    var showImmortalRank: Boolean
        get() = prefs.getBoolean(KEY_SHOW_IMMORTAL_RANK, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_IMMORTAL_RANK, value).apply()

    /** Automatic refresh interval for the toy, see [RefreshInterval]. */
    var refreshIntervalMinutes: Int
        get() = RefreshInterval.clamp(prefs.getInt(KEY_REFRESH_INTERVAL, RefreshInterval.DEFAULT_MINUTES))
        set(value) = prefs.edit().putInt(KEY_REFRESH_INTERVAL, RefreshInterval.clamp(value)).apply()

    data class Cached(val player: PlayerRank, val fetchedAtMs: Long)

    fun cached(): Cached? {
        val fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L)
        if (fetchedAt <= 0) return null
        return Cached(
            PlayerRank(
                accountId = prefs.getLong(KEY_CACHED_ACCOUNT, 0L),
                personaName = prefs.getString(KEY_PERSONA, null),
                rankTier = prefs.getInt(KEY_RANK_TIER, NONE).takeIf { it != NONE },
                leaderboardRank = prefs.getInt(KEY_LEADERBOARD, NONE).takeIf { it != NONE },
            ),
            fetchedAt,
        )
    }

    /** Cache only counts if it belongs to the currently configured account. */
    fun cachedForCurrentAccount(): Cached? =
        cached()?.takeIf { it.player.accountId == accountId }

    /** Saves a fetched rank; a success clears the last error. */
    fun save(player: PlayerRank, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .remove(KEY_ERROR).remove(KEY_ERROR_AT).remove(KEY_ERROR_ACCOUNT)
            .putLong(KEY_CACHED_ACCOUNT, player.accountId)
            .putString(KEY_PERSONA, player.personaName)
            .putInt(KEY_RANK_TIER, player.rankTier ?: NONE)
            .putInt(KEY_LEADERBOARD, player.leaderboardRank ?: NONE)
            .putLong(KEY_FETCHED_AT, nowMs)
            .apply()
    }

    /** Why the latest refresh failed; the Glyph never shows errors, so the app does. */
    data class LastError(val accountId: Long, val message: String, val atMs: Long)

    val lastError: LastError?
        get() {
            val message = prefs.getString(KEY_ERROR, null) ?: return null
            return LastError(prefs.getLong(KEY_ERROR_ACCOUNT, 0L), message, prefs.getLong(KEY_ERROR_AT, 0L))
        }

    /** The last error, if it belongs to the current account. */
    fun lastErrorForCurrentAccount(): LastError? = lastError?.takeIf { it.accountId == accountId }

    fun saveError(accountId: Long, message: String, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(KEY_ERROR, message)
            .putLong(KEY_ERROR_AT, nowMs)
            .putLong(KEY_ERROR_ACCOUNT, accountId)
            .apply()
    }

    /** Accounts checked successfully, newest first; see [RecentAccounts]. */
    var recentAccounts: List<RecentAccounts.Entry>
        get() = RecentAccounts.decode(prefs.getString(KEY_RECENT, null))
        set(value) = prefs.edit().putString(KEY_RECENT, RecentAccounts.encode(value)).apply()

    /** The toy has been on the Glyph at least once (so it has been added to the Glyph Toys). */
    var toyUsed: Boolean
        get() = prefs.getBoolean(KEY_TOY_USED, false)
        set(value) = prefs.edit().putBoolean(KEY_TOY_USED, value).apply()

    /** The one-time "Add to Glyph Toys" prompt was used or dismissed. */
    var toyPromptDone: Boolean
        get() = prefs.getBoolean(KEY_TOY_PROMPT_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_TOY_PROMPT_DONE, value).apply()

    /**
     * The rank before a change that the Glyph hasn't animated yet (the toy wasn't on the
     * matrix). Kept until the toy plays it; later changes keep the oldest "before".
     */
    var pendingCelebration: PlayerRank?
        get() {
            val account = prefs.getLong(KEY_PENDING_ACCOUNT, 0L).takeIf { it > 0 } ?: return null
            return PlayerRank(
                accountId = account,
                personaName = null,
                rankTier = prefs.getInt(KEY_PENDING_TIER, NONE).takeIf { it != NONE },
                leaderboardRank = prefs.getInt(KEY_PENDING_LEADERBOARD, NONE).takeIf { it != NONE },
            )
        }
        set(value) {
            val editor = prefs.edit()
            if (value == null) {
                editor.remove(KEY_PENDING_ACCOUNT).remove(KEY_PENDING_TIER).remove(KEY_PENDING_LEADERBOARD)
            } else {
                editor.putLong(KEY_PENDING_ACCOUNT, value.accountId)
                    .putInt(KEY_PENDING_TIER, value.rankTier ?: NONE)
                    .putInt(KEY_PENDING_LEADERBOARD, value.leaderboardRank ?: NONE)
            }
            editor.apply()
        }

    /** See [RefreshPolicy]. */
    var guard: GuardState
        get() = GuardState(
            blockedUntilMs = prefs.getLong(KEY_GUARD_BLOCKED_UNTIL, 0L),
            blockedDaily = prefs.getBoolean(KEY_GUARD_BLOCKED_DAILY, false),
            pausedUntilMs = prefs.getLong(KEY_GUARD_PAUSED_UNTIL, 0L),
            failures = prefs.getInt(KEY_GUARD_FAILURES, 0),
            lastAttemptMs = prefs.getLong(KEY_GUARD_LAST_ATTEMPT, 0L),
            lastAttemptAccount = prefs.getLong(KEY_GUARD_LAST_ACCOUNT, 0L),
        )
        set(value) {
            prefs.edit()
                .putLong(KEY_GUARD_BLOCKED_UNTIL, value.blockedUntilMs)
                .putBoolean(KEY_GUARD_BLOCKED_DAILY, value.blockedDaily)
                .putLong(KEY_GUARD_PAUSED_UNTIL, value.pausedUntilMs)
                .putInt(KEY_GUARD_FAILURES, value.failures)
                .putLong(KEY_GUARD_LAST_ATTEMPT, value.lastAttemptMs)
                .putLong(KEY_GUARD_LAST_ACCOUNT, value.lastAttemptAccount)
                .apply()
        }

    companion object {
        const val PREFS_NAME = "dota_rank"
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_REFRESH_INTERVAL = "refresh_interval_minutes"
        const val KEY_SHOW_IMMORTAL_RANK = "show_immortal_rank"
        private const val KEY_CACHED_ACCOUNT = "cached_account_id"
        private const val KEY_PERSONA = "persona_name"
        private const val KEY_RANK_TIER = "rank_tier"
        private const val KEY_LEADERBOARD = "leaderboard_rank"
        private const val KEY_FETCHED_AT = "fetched_at"
        const val KEY_RECENT = "recent_accounts"
        const val KEY_TOY_USED = "toy_used"
        const val KEY_TOY_PROMPT_DONE = "toy_prompt_done"
        private const val KEY_PENDING_ACCOUNT = "pending_celebration_account_id"
        private const val KEY_PENDING_TIER = "pending_celebration_rank_tier"
        private const val KEY_PENDING_LEADERBOARD = "pending_celebration_leaderboard_rank"
        private const val KEY_ERROR = "last_error"
        private const val KEY_ERROR_AT = "last_error_at"
        private const val KEY_ERROR_ACCOUNT = "last_error_account_id"
        private const val KEY_GUARD_BLOCKED_UNTIL = "guard_blocked_until"
        private const val KEY_GUARD_BLOCKED_DAILY = "guard_blocked_daily"
        private const val KEY_GUARD_PAUSED_UNTIL = "guard_paused_until"
        private const val KEY_GUARD_FAILURES = "guard_failures"
        private const val KEY_GUARD_LAST_ATTEMPT = "guard_last_attempt"
        private const val KEY_GUARD_LAST_ACCOUNT = "guard_last_account_id"
        private const val NONE = -1

        /** Bookkeeping keys that don't change what the Glyph shows. */
        fun isBookkeeping(key: String?) = key != null &&
            (key.startsWith("guard_") || key.startsWith("last_error") || key == KEY_RECENT ||
                key.startsWith("toy_") || key.startsWith("pending_"))
    }
}
