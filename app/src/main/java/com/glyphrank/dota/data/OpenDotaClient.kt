package com.glyphrank.dota.data

import com.glyphrank.dota.rank.RankState
import com.glyphrank.dota.rank.RankTier
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

data class PlayerRank(
    val accountId: Long,
    val personaName: String?,
    val rankTier: Int?,
    val leaderboardRank: Int?,
) {
    val state: RankState get() = RankTier.decode(rankTier, leaderboardRank)
}

class OpenDotaException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
    /** For [Kind.RATE_LIMITED]: which limit was hit. */
    val limit: RateLimit? = null,
) : Exception(message, cause) {
    enum class Kind { NOT_FOUND, RATE_LIMITED, HTTP, NETWORK, PARSE }
    enum class RateLimit { MINUTE, DAILY }
}

/** A player response plus OpenDota's rate-limit headers (null if missing). */
data class PlayerFetch(
    val player: PlayerRank,
    val remainingMinute: Int?,
    val remainingDay: Int?,
)

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

/**
 * Minimal client for the free OpenDota tier (currently ~60 requests/min, 3000/day).
 *
 * The player endpoint can be slow under load (measured 12–38 s to the first byte while
 * `/health` answered in under a second), so the read timeout is much longer than the
 * connect timeout.
 */
class OpenDotaClient(
    private val baseUrl: String = "https://api.opendota.com/api",
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 45_000,
) {
    /** Blocking call — run it off the main thread. */
    fun fetchPlayer(accountId: Long): PlayerRank = fetch(accountId).player

    /** Like [fetchPlayer], plus the rate-limit headers. Blocking. */
    fun fetch(accountId: Long): PlayerFetch {
        val connection = try {
            (URL("$baseUrl/players/$accountId").openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "DotaRankGlyph/0.1 (Nothing Phone 3 Glyph Toy)")
            }
        } catch (e: IOException) {
            throw OpenDotaException(OpenDotaException.Kind.NETWORK, "Could not reach OpenDota", e)
        }
        try {
            val code = connection.responseCode
            when {
                code == 429 -> throw rateLimited(connection)
                code == 404 -> throw OpenDotaException(OpenDotaException.Kind.NOT_FOUND, "Player $accountId not found")
                code !in 200..299 -> throw OpenDotaException(OpenDotaException.Kind.HTTP, "OpenDota returned HTTP $code")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return PlayerFetch(
                OpenDotaParser.parsePlayer(accountId, body),
                remainingMinute = connection.getHeaderField("X-Rate-Limit-Remaining-Minute")?.trim()?.toIntOrNull(),
                remainingDay = connection.getHeaderField("X-Rate-Limit-Remaining-Day")?.trim()?.toIntOrNull(),
            )
        } catch (e: SocketTimeoutException) {
            // Connected, but no answer in time: OpenDota is overloaded, not the phone offline.
            val connecting = e.message?.startsWith("failed to connect") == true
            throw OpenDotaException(
                OpenDotaException.Kind.NETWORK,
                if (connecting) "Could not reach OpenDota" else "OpenDota is slow to answer right now",
                e,
            )
        } catch (e: IOException) {
            throw OpenDotaException(OpenDotaException.Kind.NETWORK, "Could not reach OpenDota", e)
        } finally {
            connection.disconnect()
        }
    }

    /** OpenDota answers `{"error":"daily api limit exceeded"}` or `…"minute rate limit exceeded"}`. */
    private fun rateLimited(connection: HttpURLConnection): OpenDotaException {
        val body = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        val daily = body.contains("daily", ignoreCase = true)
        return OpenDotaException(
            OpenDotaException.Kind.RATE_LIMITED,
            if (daily) "OpenDota daily limit reached" else "OpenDota rate limit hit, try again in a minute",
            limit = if (daily) OpenDotaException.RateLimit.DAILY else OpenDotaException.RateLimit.MINUTE,
        )
    }
}
