package com.glyphrank.dota.data

import com.glyphrank.dota.rank.RankState
import com.glyphrank.dota.rank.RankTier
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class PlayerRank(
    val accountId: Long,
    val personaName: String?,
    val rankTier: Int?,
    val leaderboardRank: Int?,
) {
    val state: RankState get() = RankTier.decode(rankTier, leaderboardRank)
}

class OpenDotaException(val kind: Kind, message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    enum class Kind { NOT_FOUND, RATE_LIMITED, HTTP, NETWORK, PARSE }
}

object OpenDotaParser {
    /** Parses the body of `GET /api/players/{account_id}`. */
    fun parsePlayer(accountId: Long, body: String): PlayerRank {
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw OpenDotaException(OpenDotaException.Kind.PARSE, "Unexpected response from OpenDota", e)
        }
        // Unknown or private accounts come back without a usable profile object.
        val profile = root.optJSONObject("profile")
            ?: throw OpenDotaException(
                OpenDotaException.Kind.NOT_FOUND,
                "No public profile for $accountId (is \"Expose Public Match Data\" on?)",
            )
        return PlayerRank(
            accountId = accountId,
            personaName = profile.optNullableString("personaname"),
            rankTier = root.optNullableInt("rank_tier"),
            leaderboardRank = root.optNullableInt("leaderboard_rank"),
        )
    }

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key)

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key)
}

/** Minimal client for the free OpenDota tier (currently ~60 requests/min, 3000/day). */
class OpenDotaClient(
    private val baseUrl: String = "https://api.opendota.com/api",
    private val timeoutMs: Int = 10_000,
) {
    /** Blocking call — run it off the main thread. */
    fun fetchPlayer(accountId: Long): PlayerRank {
        val connection = try {
            (URL("$baseUrl/players/$accountId").openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "DotaRankGlyph/0.1 (Nothing Phone 3 Glyph Toy)")
            }
        } catch (e: IOException) {
            throw OpenDotaException(OpenDotaException.Kind.NETWORK, "Could not reach OpenDota", e)
        }
        try {
            val code = connection.responseCode
            when {
                code == 429 -> throw OpenDotaException(
                    OpenDotaException.Kind.RATE_LIMITED, "OpenDota rate limit hit, try again in a minute",
                )
                code == 404 -> throw OpenDotaException(OpenDotaException.Kind.NOT_FOUND, "Player $accountId not found")
                code !in 200..299 -> throw OpenDotaException(OpenDotaException.Kind.HTTP, "OpenDota returned HTTP $code")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return OpenDotaParser.parsePlayer(accountId, body)
        } catch (e: IOException) {
            throw OpenDotaException(OpenDotaException.Kind.NETWORK, "Could not reach OpenDota", e)
        } finally {
            connection.disconnect()
        }
    }
}
