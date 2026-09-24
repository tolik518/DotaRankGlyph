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

    /** Which art the toy uses for ranked players. */
    var useIconPack: Boolean
        get() = prefs.getBoolean(KEY_USE_ICON_PACK, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_ICON_PACK, value).apply()

    /** Automatic refresh interval for the toy, see [RefreshInterval]. */
    var refreshIntervalMinutes: Int
        get() = RefreshInterval.clamp(prefs.getInt(KEY_REFRESH_INTERVAL, RefreshInterval.DEFAULT_MINUTES))
        set(value) = prefs.edit().putInt(KEY_REFRESH_INTERVAL, RefreshInterval.clamp(value)).apply()

    /** Bumped on every import so the toy service notices a new pack file. */
    fun markIconPackChanged() =
        prefs.edit().putLong(KEY_ICON_PACK_VERSION, System.currentTimeMillis()).apply()

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

    fun save(player: PlayerRank, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(KEY_CACHED_ACCOUNT, player.accountId)
            .putString(KEY_PERSONA, player.personaName)
            .putInt(KEY_RANK_TIER, player.rankTier ?: NONE)
            .putInt(KEY_LEADERBOARD, player.leaderboardRank ?: NONE)
            .putLong(KEY_FETCHED_AT, nowMs)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "dota_rank"
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_USE_ICON_PACK = "use_icon_pack"
        const val KEY_ICON_PACK_VERSION = "icon_pack_version"
        const val KEY_REFRESH_INTERVAL = "refresh_interval_minutes"
        private const val KEY_CACHED_ACCOUNT = "cached_account_id"
        private const val KEY_PERSONA = "persona_name"
        private const val KEY_RANK_TIER = "rank_tier"
        private const val KEY_LEADERBOARD = "leaderboard_rank"
        private const val KEY_FETCHED_AT = "fetched_at"
        private const val NONE = -1
    }
}
