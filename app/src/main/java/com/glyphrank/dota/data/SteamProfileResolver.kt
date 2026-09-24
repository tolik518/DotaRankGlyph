package com.glyphrank.dota.data

import com.glyphrank.dota.rank.PlayerInput
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class SteamLookupException(val kind: Kind, message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    enum class Kind { NOT_FOUND, RATE_LIMITED, HTTP, NETWORK, PARSE }
}

/**
 * Turns a Steam custom profile URL (steamcommunity.com/id/<name>) into a Dota account ID,
 * without a Steam Web API key:
 *
 *  1. the profile's XML view, `/id/<name>/?xml=1` (has `<steamID64>`, or an `<error>`
 *     for unknown names);
 *  2. only if that answer has neither: the profile page, which embeds `"steamid":"…"`.
 *
 * Steam doesn't document limits for these pages and throttles anonymous clients, so the app
 * resolves a name once, when it is saved, and stores the account ID. A 429 is reported
 * straight away (no fallback request).
 */
class SteamProfileResolver(
    private val baseUrl: String = "https://steamcommunity.com",
    private val timeoutMs: Int = 10_000,
) {
    /** Blocking — run it off the main thread. */
    fun resolveVanity(name: String): Long {
        val profile = "$baseUrl/id/${URLEncoder.encode(name, "UTF-8")}/"
        val xml = get("$profile?xml=1")
        XML_STEAM_ID64.find(xml)?.let { return toAccountId(it.groupValues[1], name) }
        if (XML_ERROR.containsMatchIn(xml)) {
            throw SteamLookupException(SteamLookupException.Kind.NOT_FOUND, "No Steam profile at steamcommunity.com/id/$name")
        }
        // Neither an ID nor an error: the XML view changed or is unavailable; try the page itself.
        val html = get(profile)
        HTML_STEAM_ID64.find(html)?.let { return toAccountId(it.groupValues[1], name) }
        throw SteamLookupException(SteamLookupException.Kind.PARSE, "Couldn't read the Steam profile for /id/$name")
    }

    private fun toAccountId(steamId64: String, name: String): Long =
        (PlayerInput.parse(steamId64) as? PlayerInput.Account)?.accountId
            ?: throw SteamLookupException(SteamLookupException.Kind.PARSE, "Steam returned an invalid ID for /id/$name")

    private fun get(url: String): String {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "DotaRankGlyph/0.1 (Nothing Phone 3 Glyph Toy)")
            }
        } catch (e: IOException) {
            throw SteamLookupException(SteamLookupException.Kind.NETWORK, "Could not reach Steam", e)
        }
        try {
            val code = connection.responseCode
            when {
                code == 429 -> throw SteamLookupException(
                    SteamLookupException.Kind.RATE_LIMITED,
                    "Steam is limiting lookups right now. Try again in a few minutes, or paste your friend ID instead.",
                )
                code == 404 -> throw SteamLookupException(SteamLookupException.Kind.NOT_FOUND, "No Steam profile at that URL")
                code !in 200..299 -> throw SteamLookupException(SteamLookupException.Kind.HTTP, "Steam returned HTTP $code")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            throw SteamLookupException(SteamLookupException.Kind.NETWORK, "Could not reach Steam", e)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        val XML_STEAM_ID64 = Regex("""<steamID64>\s*(\d{17})\s*</steamID64>""")
        val XML_ERROR = Regex("""<response>\s*<error>""")
        val HTML_STEAM_ID64 = Regex(""""steamid"\s*:\s*"(\d{17})"""")
    }
}
