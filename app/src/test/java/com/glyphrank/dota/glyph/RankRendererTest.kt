package com.glyphrank.dota.glyph

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

    // --- icon pack mode (synthetic, fully lit icons) -------------------------------

    private val brightPack = IconPack(Medal.entries.associateWith { IntArray(625) { full } })

    @Test fun `pack icon plus one isolated pip per star`() {
        for (stars in 0..7) {
            val f = renderer.render(RankState.Ranked(Medal.GUARDIAN, stars), brightPack)
            assertEquals("stars=$stars", stars, isolatedPips(f))
        }
    }

    @Test fun `pack without stars shows the icon as is`() {
        val icon = brightPack.iconFor(Medal.ARCHON)!!
        assertTrue(renderer.render(RankState.Ranked(Medal.ARCHON, 0), brightPack).contentEquals(icon))
        assertTrue(renderer.render(RankState.Immortal(5), brightPack).contentEquals(brightPack.iconFor(Medal.IMMORTAL)!!))
    }

    @Test fun `tiers missing from the pack and status screens use the built-in art`() {
        val partial = IconPack(mapOf(Medal.HERALD to IntArray(625) { full }))
        val legend = RankState.Ranked(Medal.LEGEND, 2)
        assertTrue(renderer.render(legend, partial).contentEquals(renderer.render(legend)))
        assertTrue(renderer.render(RankState.Uncalibrated, partial).contentEquals(renderer.render(RankState.Uncalibrated)))
    }

    @Test fun `pack frames stay inside the led circle`() {
        for (state in allStates) {
            val f = renderer.render(state, brightPack)
            for (y in 0 until 25) for (x in 0 until 25) {
                if (!MatrixLayout.isLed(x, y)) assertEquals(0, f[y * 25 + x])
            }
        }
    }

    // --- bundled Dota medal art ---------------------------------------------------

    private val bundled = IconPack(IconPackParser.fromBitmapsJson(File("src/main/assets/dota_rank_medals.json").readText()))

    @Test fun `bundled medals show exactly one lone led per star`() {
        // Regression: the pip band sliced a single LED off Guardian's wing, so rank 24
        // showed six dots on the Phone (3) instead of four.
        for (medal in Medal.entries.filter { it != Medal.IMMORTAL }) for (stars in 1..RankTier.MAX_STARS) {
            val f = renderer.render(RankState.Ranked(medal, stars), bundled)
            assertEquals("$medal stars=$stars", stars, isolatedPips(f, minValue = 1, upperHalfOnly = true))
        }
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
