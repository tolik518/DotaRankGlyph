package com.glyphrank.dota.rank

/**
 * Turns whatever the user pastes into a Dota account ID (the 32-bit "friend ID"
 * that OpenDota uses, e.g. 40453096).
 *
 * Accepted (all decoded offline):
 *  - friend / account ID: 40453096
 *  - SteamID64: 76561198000718824
 *  - SteamID2 / SteamID3: STEAM_0:0:20226548, [U:1:40453096]
 *  - URLs: steamcommunity.com/profiles/<id64>, opendota.com/players/<id>,
 *          dotabuff.com/players/<id>, stratz.com/players/<id>
 *  - Steam friend-code links: s.team/p/<code>[/<invite token>], steamcommunity.com/user/<code>
 *
 * Custom profile URLs (steamcommunity.com/id/<name>) come back as [SteamVanity]; turning
 * those into an account ID needs a lookup (see data/SteamProfileResolver).
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
        private val FRIEND_CODE_URL =
            Regex("""(?:s\.team/p|steamcommunity\.com/user)/([a-z-]+)""", RegexOption.IGNORE_CASE)
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
            FRIEND_CODE_URL.find(input)?.let { m -> return fromFriendCode(m.groupValues[1]) }
            VANITY_URL.find(input)?.let { m -> return SteamVanity(m.groupValues[1]) }

            if (input.all { it.isDigit() }) return fromNumber(input)
            return Invalid("Not a friend ID, SteamID or profile URL")
        }

        /**
         * Text shared from another app ("Check out my profile: https://…"). Profile URLs are
         * found anywhere in the text; otherwise the first word that is an ID wins.
         */
        fun fromSharedText(text: String): PlayerInput {
            val whole = parse(text)
            if (whole !is Invalid) return whole
            return text.split(Regex("""\s+"""))
                .map { it.trim('.', ',', ';', ':', '!', '?', '(', ')', '"', '\'') }
                .map(::parse)
                .firstOrNull { it !is Invalid }
                ?: Invalid("No friend ID or Steam profile link in the shared text")
        }

        private fun fromNumber(digits: String): PlayerInput {
            val n = digits.toLongOrNull() ?: return Invalid("Number is too long")
            return if (n >= STEAM_ID64_BASE) fromAccountId(n - STEAM_ID64_BASE) else fromAccountId(n)
        }

        /**
         * Friend codes are the account ID in hex, written with the letters
         * [FRIEND_CODE_DIGITS] instead of 0-9a-f; the dash is only cosmetic (djn-gfvm = 40453096).
         */
        private fun fromFriendCode(code: String): PlayerInput {
            val letters = code.replace("-", "").lowercase()
            if (letters.isEmpty() || letters.length > 8 || letters.any { it !in FRIEND_CODE_DIGITS }) {
                return Invalid("Not a valid Steam friend link")
            }
            val hex = letters.map { FRIEND_CODE_DIGITS.indexOf(it).toString(16) }.joinToString("")
            return fromAccountId(hex.toLong(16))
        }

        private const val FRIEND_CODE_DIGITS = "bcdfghjkmnpqrtvw"

        private fun fromAccountId(id: Long): PlayerInput =
            if (id in 1..MAX_ACCOUNT_ID) Account(id) else Invalid("ID out of range")
    }
}
