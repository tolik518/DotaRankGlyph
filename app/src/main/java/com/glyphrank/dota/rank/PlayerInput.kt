package com.glyphrank.dota.rank

/**
 * Turns whatever the user pastes into a Dota account ID (the 32-bit "friend ID"
 * that OpenDota uses, e.g. 40453096).
 *
 * Accepted today:
 *  - friend / account ID: 40453096
 *  - SteamID64: 76561198000718824
 *  - SteamID2 / SteamID3: STEAM_0:0:20226548, [U:1:40453096]
 *  - URLs: steamcommunity.com/profiles/<id64>, opendota.com/players/<id>,
 *          dotabuff.com/players/<id>, stratz.com/players/<id>
 *
 * Recognised but not resolved yet: steamcommunity.com/id/<custom-name>.
 * Resolving that needs a network call (see README "Next steps").
 */
sealed interface PlayerInput {
    data class Account(val accountId: Long) : PlayerInput
    data class SteamVanity(val vanityName: String) : PlayerInput
    data class Invalid(val reason: String) : PlayerInput

    companion object {
        const val STEAM_ID64_BASE = 76561197960265728L
        private const val MAX_ACCOUNT_ID = 0xFFFFFFFFL

        private val STEAM2 = Regex("""^STEAM_[0-5]:([01]):(\d+)$""", RegexOption.IGNORE_CASE)
        private val STEAM3 = Regex("""^\[?U:1:(\d+)]?$""", RegexOption.IGNORE_CASE)
        private val PROFILES_URL = Regex("""steamcommunity\.com/profiles/(\d+)""", RegexOption.IGNORE_CASE)
        private val VANITY_URL = Regex("""steamcommunity\.com/id/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
        private val STATS_SITE_URL =
            Regex("""(?:opendota|dotabuff|stratz)\.com/players/(\d+)""", RegexOption.IGNORE_CASE)

        fun parse(raw: String): PlayerInput {
            val input = raw.trim()
            if (input.isEmpty()) return Invalid("Enter your Dota friend ID")

            STEAM2.find(input)?.let { m ->
                val y = m.groupValues[1].toLong()
                val z = m.groupValues[2].toLongOrNull() ?: return Invalid("SteamID is too long")
                return fromAccountId(z * 2 + y)
            }
            STEAM3.find(input)?.let { m -> return fromNumber(m.groupValues[1]) }
            PROFILES_URL.find(input)?.let { m -> return fromNumber(m.groupValues[1]) }
            STATS_SITE_URL.find(input)?.let { m -> return fromNumber(m.groupValues[1]) }
            VANITY_URL.find(input)?.let { m -> return SteamVanity(m.groupValues[1]) }

            if (input.all { it.isDigit() }) return fromNumber(input)
            return Invalid("Not a friend ID, SteamID or profile URL")
        }

        private fun fromNumber(digits: String): PlayerInput {
            val n = digits.toLongOrNull() ?: return Invalid("Number is too long")
            return if (n >= STEAM_ID64_BASE) fromAccountId(n - STEAM_ID64_BASE) else fromAccountId(n)
        }

        private fun fromAccountId(id: Long): PlayerInput =
            if (id in 1..MAX_ACCOUNT_ID) Account(id) else Invalid("ID out of range")
    }
}
