package com.glyphrank.dota.glyph

import com.glyphrank.dota.data.OpenDotaParser
import com.glyphrank.dota.rank.Medal
import com.glyphrank.dota.rank.RankState
import com.glyphrank.dota.rank.RankTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RankRendererTest {
    private val full = MatrixLayout.MAX_BRIGHTNESS
    private val renderer = RankRenderer()

    private val allStates: List<RankState> =
        (1..7).flatMap { t -> (0..7).map { s -> RankTier.decode(t * 10 + s) } } +
            listOf(RankTier.decode(80, 1), RankTier.decode(80, 9999), RankTier.decode(80), RankTier.decode(null))

    @Test fun `phone 3 matrix has 489 leds`() {
        assertEquals(489, MatrixLayout.LED_COUNT)
        assertTrue(MatrixLayout.isLed(12, 12))
        assertFalse(MatrixLayout.isLed(0, 0))
        assertFalse(MatrixLayout.isLed(24, 24))
        assertTrue(MatrixLayout.isLed(9, 0))
        assertFalse(MatrixLayout.isLed(8, 0))
    }

    @Test fun `frames are 625 values, in range, and nothing outside the circle`() {
        val frames = allStates.map(renderer::render) +
            listOf(renderer.message("ID"), renderer.message("!"), renderer.loading(0), renderer.loading(13))
        for (f in frames) {
            assertEquals(625, f.size)
            for (y in 0 until 25) for (x in 0 until 25) {
                val v = f[y * 25 + x]
                assertTrue(v in 0..full)
                if (!MatrixLayout.isLed(x, y)) assertEquals("pixel ($x,$y) is not an LED", 0, v)
            }
        }
    }

    @Test fun `lit arcs equal the medal tier`() {
        for (medal in Medal.entries.filter { it != Medal.IMMORTAL }) {
            assertEquals(medal.displayName, medal.tierNumber, litArcs(renderer.render(RankState.Ranked(medal, 3))))
        }
        assertEquals(8, litArcs(renderer.render(RankState.Immortal(null))))
        assertEquals(0, litArcs(renderer.message("ID")))
    }

    @Test fun `every arc has leds and arcs are separated by gaps`() {
        val perArc = IntArray(8)
        var gaps = 0
        MatrixCanvas().forEachLed { x, y ->
            if (MatrixLayout.radius(x, y) < 10.8) return@forEachLed
            val arc = renderer.arcAt(x, y)
            if (arc == null) gaps++ else perArc[arc]++
        }
        assertTrue(perArc.joinToString(), perArc.all { it >= 6 })
        assertTrue("gaps=$gaps", gaps >= 8)
    }

    @Test fun `centre shows one bright cross per star`() {
        for (stars in 0..7) {
            val f = renderer.render(RankState.Ranked(Medal.GUARDIAN, stars))
            assertEquals("stars=$stars", stars * 5, fullInCentre(f))
        }
    }

    @Test fun `24 renders as guardian 4`() {
        val f = renderer.render(RankTier.decode(24))
        assertEquals(2, litArcs(f))
        assertEquals(20, fullInCentre(f))
    }

    @Test fun `spinner moves`() {
        assertFalse(renderer.loading(0).contentEquals(renderer.loading(6)))
    }

    @Test fun `shake moves the rank one led left and right and back to centre`() {
        val rank = renderer.render(RankTier.decode(24))
        val frames = (0 until RankRenderer.SHAKE_CYCLE_STEPS).map { renderer.shake(rank, it) }
        // The toy and app stop after whole cycles, so a cycle must end centred.
        assertTrue(frames.last().contentEquals(rank))
        for (dx in listOf(-1, 1)) {
            assertTrue("dx=$dx", frames.any { f ->
                var same = true
                MatrixCanvas().forEachLed { x, y ->
                    val sx = x - dx
                    val expected = if (sx in 0 until 25) rank[sx + y * 25] else 0
                    if (MatrixLayout.isLed(sx, y) && f[MatrixLayout.index(x, y)] != expected) same = false
                }
                same
            })
        }
        for (f in frames) for (y in 0 until 25) for (x in 0 until 25) {
            if (!MatrixLayout.isLed(x, y)) assertEquals(0, f[y * 25 + x])
        }
    }

    // --- medal art (synthetic, fully lit frames) -----------------------------------

    private val brightArt = MedalArt(Medal.entries.associateWith { IntArray(625) { full } })

    @Test fun `medal art plus one isolated pip per star`() {
        for (stars in 0..7) {
            val f = renderer.render(RankState.Ranked(Medal.GUARDIAN, stars), brightArt)
            assertEquals("stars=$stars", stars, isolatedPips(f))
        }
    }

    @Test fun `art without stars is shown as is`() {
        val icon = brightArt.frameFor(Medal.ARCHON)!!
        assertTrue(renderer.render(RankState.Ranked(Medal.ARCHON, 0), brightArt).contentEquals(icon))
        val immortal = brightArt.frameFor(Medal.IMMORTAL)!!
        assertTrue(renderer.render(RankState.Immortal(null), brightArt).contentEquals(immortal))
        assertTrue(renderer.render(RankState.Immortal(5), brightArt, showImmortalRank = false).contentEquals(immortal))
    }

    @Test fun `medals without art and status screens use the built-in emblem`() {
        val partial = MedalArt(mapOf(Medal.HERALD to IntArray(625) { full }))
        val legend = RankState.Ranked(Medal.LEGEND, 2)
        assertTrue(renderer.render(legend, partial).contentEquals(renderer.render(legend)))
        assertTrue(renderer.render(RankState.Uncalibrated, partial).contentEquals(renderer.render(RankState.Uncalibrated)))
    }

    @Test fun `art frames stay inside the led circle`() {
        for (state in allStates) {
            val f = renderer.render(state, brightArt)
            for (y in 0 until 25) for (x in 0 until 25) {
                if (!MatrixLayout.isLed(x, y)) assertEquals(0, f[y * 25 + x])
            }
        }
    }

    // --- bundled Dota medal art ---------------------------------------------------

    private val bundled = MedalArt.fromJson(File("src/main/assets/dota_rank_medals.json").readText())

    @Test fun `bundled medals show exactly one lone led per star`() {
        // Regression: the pip band sliced a single LED off Guardian's wing, so rank 24
        // showed six dots on the Phone (3) instead of four.
        for (medal in Medal.entries.filter { it != Medal.IMMORTAL }) for (stars in 1..RankTier.MAX_STARS) {
            val f = renderer.render(RankState.Ranked(medal, stars), bundled)
            assertEquals("$medal stars=$stars", stars, isolatedPips(f, minValue = 1, upperHalfOnly = true))
        }
    }

    // --- Immortal leaderboard place ------------------------------------------------------

    /** ZQuixotix: captured OpenDota response, rank_tier 80, leaderboard_rank 2488. */
    private val zquixotix = OpenDotaParser.parsePlayer(
        116233682,
        javaClass.getResource("/opendota/player_116233682.json")!!.readText(),
    )

    @Test fun `ZQuixotix shows 2488 on the immortal medal`() {
        assertEquals(RankState.Immortal(2488), zquixotix.state)
        val f = renderer.render(zquixotix.state, bundled, showImmortalRank = true)
        val top = plateTop(f, "2488")
        assertEquals("plate sits on rows 17-21", 17, top)
        assertTrue(fitsOnLeds("2488", top!!))
        assertUntouchedOutsidePlate(f, bundled.frameFor(Medal.IMMORTAL)!!, "2488", top)
    }

    @Test fun `setting off or no leaderboard place shows the immortal medal as is`() {
        val medal = bundled.frameFor(Medal.IMMORTAL)!!
        assertTrue(renderer.render(zquixotix.state, bundled, showImmortalRank = false).contentEquals(medal))
        assertTrue(renderer.render(RankState.Immortal(null), bundled, showImmortalRank = true).contentEquals(medal))
    }

    @Test fun `every leaderboard length fits inside the led circle`() {
        for ((place, expectedTop) in listOf(1 to 17, 42 to 17, 999 to 17, 5000 to 17, 12345 to 16)) {
            val text = place.toString()
            val f = renderer.render(RankState.Immortal(place), bundled)
            val top = plateTop(f, text)
            assertEquals("place=$place", expectedTop, top)
            assertTrue("place=$place", fitsOnLeds(text, top!!))
        }
    }

    @Test fun `the plate leaves no stray leds behind`() {
        // Regression: 5 digits cut the medal's bottom tip off, leaving one lit LED under the number.
        for (place in listOf(1, 2488, 12345)) {
            val text = place.toString()
            val f = renderer.render(RankState.Immortal(place), bundled)
            val top = plateTop(f, text)!!
            val left = (MatrixLayout.SIZE - PixelFont.textWidth(text)) / 2
            MatrixCanvas().forEachLed { x, y ->
                val inPlate = y in top - 1..top + PixelFont.HEIGHT && x in left - 1..left + PixelFont.textWidth(text)
                if (!inPlate && f[MatrixLayout.index(x, y)] > 0) {
                    val hasLitNeighbour = (-1..1).any { dy ->
                        (-1..1).any { dx ->
                            (dx != 0 || dy != 0) && MatrixLayout.isLed(x + dx, y + dy) && f[MatrixLayout.index(x + dx, y + dy)] > 0
                        }
                    }
                    assertTrue("place=$place: lone LED at ($x,$y)", hasLitNeighbour)
                }
            }
        }
    }

    @Test fun `built-in immortal emblem follows the setting too`() {
        val withPlace = renderer.render(RankState.Immortal(2488), showImmortalRank = true)
        val without = renderer.render(RankState.Immortal(2488), showImmortalRank = false)
        assertFalse(withPlace.contentEquals(without))
        assertTrue(without.contentEquals(renderer.render(RankState.Immortal(null))))
    }

    /** Top row where [text] appears exactly (lit strokes, dark gaps and a 1-LED dark margin), or null. */
    private fun plateTop(frame: IntArray, text: String): Int? {
        val width = PixelFont.textWidth(text)
        val left = (MatrixLayout.SIZE - width) / 2
        return (1..MatrixLayout.SIZE - PixelFont.HEIGHT).firstOrNull { top ->
            val expected = MatrixCanvas().also { PixelFont.drawCentered(it, text, top, full) }.pixels
            (top - 1..top + PixelFont.HEIGHT).all { y ->
                (left - 1..left + width).all { x ->
                    !MatrixLayout.isLed(x, y) || frame[MatrixLayout.index(x, y)] == expected[MatrixLayout.index(x, y)]
                }
            }
        }
    }

    /** No stroke of [text] at [top] falls outside the LED circle (compared with the centred position). */
    private fun fitsOnLeds(text: String, top: Int): Boolean {
        fun lit(t: Int) = MatrixCanvas().also { PixelFont.drawCentered(it, text, t, full) }.pixels.count { it > 0 }
        return lit(top) == lit(MatrixLayout.CENTER - 2)
    }

    private fun assertUntouchedOutsidePlate(frame: IntArray, icon: IntArray, text: String, top: Int) {
        val width = PixelFont.textWidth(text)
        val left = (MatrixLayout.SIZE - width) / 2
        var removed = 0
        MatrixCanvas().forEachLed { x, y ->
            val inPlate = y in top - 1..top + PixelFont.HEIGHT && x in left - 1..left + width
            val v = frame[MatrixLayout.index(x, y)]
            if (inPlate) return@forEachLed
            if (v == 0 && icon[MatrixLayout.index(x, y)] > 0) removed++ else assertEquals("($x,$y)", icon[MatrixLayout.index(x, y)], v)
        }
        // Outside the plate the art is unchanged, apart from a tiny piece the plate may cut off.
        assertTrue("removed=$removed", removed <= 2)
    }

    /** Lit LEDs whose LED neighbours are all off = star pips punched into the art. */
    private fun isolatedPips(frame: IntArray, minValue: Int = full, upperHalfOnly: Boolean = false): Int {
        var n = 0
        MatrixCanvas().forEachLed { x, y ->
            if (upperHalfOnly && y >= MatrixLayout.CENTER) return@forEachLed
            if (frame[MatrixLayout.index(x, y)] < minValue) return@forEachLed
            var alone = true
            for (dy in -1..1) for (dx in -1..1) {
                if ((dx != 0 || dy != 0) && MatrixLayout.isLed(x + dx, y + dy) &&
                    frame[MatrixLayout.index(x + dx, y + dy)] != 0
                ) alone = false
            }
            if (alone) n++
        }
        return n
    }

    private fun litArcs(frame: IntArray): Int {
        val lit = BooleanArray(8)
        MatrixCanvas().forEachLed { x, y ->
            val arc = renderer.arcAt(x, y) ?: return@forEachLed
            if (frame[MatrixLayout.index(x, y)] == full) lit[arc] = true
        }
        return lit.count { it }
    }

    private fun fullInCentre(frame: IntArray): Int {
        var n = 0
        MatrixCanvas().forEachLed { x, y ->
            if (MatrixLayout.radius(x, y) < 10.0 && frame[MatrixLayout.index(x, y)] == full) n++
        }
        return n
    }
}
