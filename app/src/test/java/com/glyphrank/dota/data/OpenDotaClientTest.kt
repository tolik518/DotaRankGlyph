package com.glyphrank.dota.data

import com.glyphrank.dota.data.MockHttpServer.Response
import com.glyphrank.dota.rank.Medal
import com.glyphrank.dota.rank.RankState
import com.glyphrank.dota.rank.RankTier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket

/**
 * Runs the real [OpenDotaClient] against a local mock of `GET /api/players/{id}`.
 * Player bodies are captured OpenDota responses (trimmed) in `src/test/resources/opendota/`.
 */
class OpenDotaClientTest {
    private val server = MockHttpServer()
    private val client = OpenDotaClient(baseUrl = "${server.baseUrl}/api", timeoutMs = 2_000)

    @After fun stopServer() = server.close()

    // --- real players ---------------------------------------------------------------

    private data class Expected(val name: String, val rankTier: Int?, val leaderboard: Int?, val state: RankState, val text: String)

    private val players = mapOf(
        116233682L to Expected("ZQuixotix", 80, 2488, RankState.Immortal(2488), "Immortal #2488"),
        1199208054L to Expected("Player 1", 54, null, RankState.Ranked(Medal.LEGEND, 4), "Legend 4"),
        1510911485L to Expected("Player 2", 61, null, RankState.Ranked(Medal.ANCIENT, 1), "Ancient 1"),
        1747489664L to Expected("Player 3", 61, null, RankState.Ranked(Medal.ANCIENT, 1), "Ancient 1"),
        1145501116L to Expected("Player 4", null, null, RankState.Uncalibrated, "Uncalibrated"),
        105013326L to Expected("Player 5", 44, null, RankState.Ranked(Medal.ARCHON, 4), "Archon 4"),
    )

    @Test fun `real player responses decode to the right rank`() {
        server.handler = { req -> Response(200, fixture(req.path.substringAfterLast('/').toLong())) }
        for ((id, expected) in players) {
            val player = client.fetchPlayer(id)
            assertEquals(PlayerRank(id, expected.name, expected.rankTier, expected.leaderboard), player)
            assertEquals("$id", expected.state, player.state)
            assertEquals("$id", expected.text, RankTier.describe(player.state))
        }
    }

    @Test fun `non-latin names survive decoding without a charset header`() {
        val body = """{"profile":{"account_id":2,"personaname":"Игрок 2 ★"},"rank_tier":61}"""
        server.handler = { Response(200, body, contentType = "application/json") }
        assertEquals("Игрок 2 ★", client.fetchPlayer(2).personaName)
    }

    @Test fun `asks for the player endpoint as json`() {
        server.handler = { Response(200, fixture(105013326)) }
        client.fetchPlayer(105013326)
        val request = server.requests.single()
        assertEquals("/api/players/105013326", request.path)
        assertEquals("application/json", request.headers["accept"])
    }

    // --- errors -----------------------------------------------------------------------

    @Test fun `unknown player is not found`() {
        val e = expectError(OpenDotaException.Kind.NOT_FOUND) { client.fetchPlayer(123) } // default: 404
        assertTrue(e.message!!, e.message!!.contains("123"))
    }

    @Test fun `profile without data is not found`() {
        server.handler = { Response(200, """{"profile":null,"rank_tier":null}""") }
        expectError(OpenDotaException.Kind.NOT_FOUND) { client.fetchPlayer(5) }
    }

    @Test fun `rate limit`() {
        server.handler = { Response(429, """{"error":"rate limit exceeded"}""") }
        expectError(OpenDotaException.Kind.RATE_LIMITED) { client.fetchPlayer(105013326) }
    }

    @Test fun `server errors report the status code`() {
        for (code in listOf(500, 502, 503)) {
            server.handler = { Response(code, "<html>$code</html>", contentType = "text/html") }
            val e = expectError(OpenDotaException.Kind.HTTP) { client.fetchPlayer(105013326) }
            assertTrue(e.message!!, e.message!!.contains("$code"))
        }
    }

    @Test fun `html instead of json is a parse error`() {
        server.handler = { Response(200, "<html>maintenance</html>", contentType = "text/html") }
        expectError(OpenDotaException.Kind.PARSE) { client.fetchPlayer(105013326) }
    }

    @Test fun `truncated json is a parse error`() {
        server.handler = { Response(200, fixture(105013326).take(60)) }
        expectError(OpenDotaException.Kind.PARSE) { client.fetchPlayer(105013326) }
    }

    @Test fun `slow server times out as a network error`() {
        server.handler = { Response(200, fixture(105013326), delayMs = 1_500) }
        val impatient = OpenDotaClient(baseUrl = "${server.baseUrl}/api", timeoutMs = 300)
        expectError(OpenDotaException.Kind.NETWORK) { impatient.fetchPlayer(105013326) }
    }

    @Test fun `unreachable server is a network error`() {
        val closedPort = ServerSocket(0).use { it.localPort } // nothing listens here any more
        val offline = OpenDotaClient(baseUrl = "http://127.0.0.1:$closedPort/api", timeoutMs = 2_000)
        expectError(OpenDotaException.Kind.NETWORK) { offline.fetchPlayer(105013326) }
    }

    // --- helpers ----------------------------------------------------------------------

    private fun fixture(accountId: Long): String =
        javaClass.getResource("/opendota/player_$accountId.json")!!.readText(Charsets.UTF_8)

    private fun expectError(kind: OpenDotaException.Kind, block: () -> Unit): OpenDotaException {
        try {
            block()
        } catch (e: OpenDotaException) {
            assertEquals(e.message, kind, e.kind)
            return e
        }
        fail("expected $kind")
        throw AssertionError()
    }
}
