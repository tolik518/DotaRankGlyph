package com.glyphrank.dota.data

import com.glyphrank.dota.data.MockHttpServer.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket

/**
 * Runs the real [SteamProfileResolver] against a local mock of steamcommunity.com.
 * Bodies are captured Steam responses (trimmed) in `src/test/resources/steam/`.
 */
class SteamProfileResolverTest {
    private val server = MockHttpServer()
    private val steam = SteamProfileResolver(baseUrl = server.baseUrl, timeoutMs = 2_000)

    @After fun stopServer() = server.close()

    @Test fun `custom url resolves through the xml profile`() {
        server.handler = { Response(200, fixture("profile_zeitboy.xml"), contentType = "text/xml; charset=utf-8") }
        assertEquals(40453096L, steam.resolveVanity("Zeitboy"))
        assertEquals(listOf("/id/Zeitboy/?xml=1"), server.requests.map { it.path })
    }

    @Test fun `unknown custom url is not found, without a second request`() {
        server.handler = { Response(200, fixture("profile_not_found.xml"), contentType = "text/xml") }
        expectError(SteamLookupException.Kind.NOT_FOUND) { steam.resolveVanity("nobody-here") }
        assertEquals(1, server.requests.size)
    }

    @Test fun `falls back to the profile page if the xml has no id`() {
        server.handler = { req ->
            if (req.path.endsWith("?xml=1")) Response(200, "<html>temporarily unavailable</html>", contentType = "text/html")
            else Response(200, fixture("profile_zeitboy.html"), contentType = "text/html; charset=utf-8")
        }
        assertEquals(40453096L, steam.resolveVanity("Zeitboy"))
        assertEquals(listOf("/id/Zeitboy/?xml=1", "/id/Zeitboy/"), server.requests.map { it.path })
    }

    @Test fun `neither xml nor page has an id`() {
        server.handler = { Response(200, "<html>nothing useful</html>", contentType = "text/html") }
        expectError(SteamLookupException.Kind.PARSE) { steam.resolveVanity("Zeitboy") }
    }

    @Test fun `rate limit stops right away, without the fallback request`() {
        server.handler = { Response(429, "Too Many Requests", contentType = "text/plain") }
        expectError(SteamLookupException.Kind.RATE_LIMITED) { steam.resolveVanity("Zeitboy") }
        assertEquals(1, server.requests.size)
    }

    @Test fun `server error reports the status code`() {
        server.handler = { Response(503, "<html>503</html>", contentType = "text/html") }
        val e = expectError(SteamLookupException.Kind.HTTP) { steam.resolveVanity("Zeitboy") }
        assertEquals("Steam returned HTTP 503", e.message)
    }

    @Test fun `unreachable steam is a network error`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val offline = SteamProfileResolver(baseUrl = "http://127.0.0.1:$closedPort", timeoutMs = 2_000)
        expectError(SteamLookupException.Kind.NETWORK) { offline.resolveVanity("Zeitboy") }
    }

    @Test fun `slow steam times out as a network error`() {
        server.handler = { Response(200, fixture("profile_zeitboy.xml"), delayMs = 1_500) }
        val impatient = SteamProfileResolver(baseUrl = server.baseUrl, timeoutMs = 300)
        expectError(SteamLookupException.Kind.NETWORK) { impatient.resolveVanity("Zeitboy") }
    }

    private fun fixture(name: String): String =
        javaClass.getResource("/steam/$name")!!.readText(Charsets.UTF_8)

    private fun expectError(kind: SteamLookupException.Kind, block: () -> Unit): SteamLookupException {
        try {
            block()
        } catch (e: SteamLookupException) {
            assertEquals(e.message, kind, e.kind)
            return e
        }
        fail("expected $kind")
        throw AssertionError()
    }
}
