package com.glyphrank.dota.rank

/** Dota 2 medal tiers, numbered the way OpenDota's `rank_tier` tens digit encodes them. */
enum class Medal(val tierNumber: Int, val displayName: String) {
    HERALD(1, "Herald"),
    GUARDIAN(2, "Guardian"),
    CRUSADER(3, "Crusader"),
    ARCHON(4, "Archon"),
    LEGEND(5, "Legend"),
    ANCIENT(6, "Ancient"),
    DIVINE(7, "Divine"),
    IMMORTAL(8, "Immortal");

    companion object {
        fun fromTierNumber(n: Int): Medal? = entries.firstOrNull { it.tierNumber == n }
    }
}

sealed interface RankState {
    /** No rank_tier yet (not calibrated, or match data is private). */
    data object Uncalibrated : RankState

    /** Herald..Divine with 0..[RankTier.MAX_STARS] stars. */
    data class Ranked(val medal: Medal, val stars: Int) : RankState

    /** Immortal has no stars; OpenDota reports the leaderboard position separately. */
    data class Immortal(val leaderboardRank: Int?) : RankState
}

object RankTier {
    /** Current ranks use 1–5 stars; the pre-2019 system used up to 7, so we tolerate that. */
    const val MAX_STARS = 7
    const val CURRENT_STAR_SLOTS = 5

    /**
     * Decodes OpenDota's `rank_tier`: tens digit = medal, ones digit = stars.
     * Example: 24 -> Guardian, 4 stars.
     */
    fun decode(rankTier: Int?, leaderboardRank: Int? = null): RankState {
        if (rankTier == null || rankTier <= 0) return RankState.Uncalibrated
        val medal = Medal.fromTierNumber(rankTier / 10) ?: return RankState.Uncalibrated
        if (medal == Medal.IMMORTAL) {
            return RankState.Immortal(leaderboardRank?.takeIf { it > 0 })
        }
        return RankState.Ranked(medal, (rankTier % 10).coerceIn(0, MAX_STARS))
    }

    fun describe(state: RankState): String = when (state) {
        RankState.Uncalibrated -> "Uncalibrated"
        is RankState.Ranked -> "${state.medal.displayName} ${state.stars}"
        is RankState.Immortal ->
            state.leaderboardRank?.let { "Immortal #$it" } ?: "Immortal"
    }
}
