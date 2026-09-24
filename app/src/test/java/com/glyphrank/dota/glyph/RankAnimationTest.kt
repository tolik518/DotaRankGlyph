package com.glyphrank.dota.glyph

import com.glyphrank.dota.glyph.RankAnimation.Change
import com.glyphrank.dota.rank.Medal
import com.glyphrank.dota.rank.RankState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RankAnimationTest {
    private val medals = MedalArt.fromJson(File("src/main/assets/dota_rank_medals.json").readText())
    private val renderer = RankRenderer()
    private val animation = RankAnimation(renderer)

    private fun ranked(medal: Medal, stars: Int) = RankState.Ranked(medal, stars)
    private val guardian3 = ranked(Medal.GUARDIAN, 3)
    private val guardian4 = ranked(Medal.GUARDIAN, 4)
    private val crusader1 = ranked(Medal.CRUSADER, 1)
    private val divine5 = ranked(Medal.DIVINE, 5)

    private fun frames(old: RankState, new: RankState, show: Boolean = true) = animation.frames(old, new, medals, show)

    @Test fun `changes are classified`() {
        fun c(old: RankState, new: RankState, show: Boolean = true) = RankAnimation.classify(old, new, show)
        assertEquals(Change.NONE, c(guardian4, guardian4))
        assertEquals(Change.STAR_UP, c(guardian3, guardian4))
        assertEquals(Change.STAR_DOWN, c(guardian4, guardian3))
        assertEquals(Change.TIER_UP, c(guardian4, crusader1))
        assertEquals(Change.TIER_DOWN, c(crusader1, guardian4))
        assertEquals(Change.TIER_UP, c(RankState.Uncalibrated, guardian3)) // first calibration
        assertEquals(Change.TIER_UP, c(divine5, RankState.Immortal(2488)))
        assertEquals(Change.TIER_DOWN, c(RankState.Immortal(2488), divine5))
        assertEquals(Change.PLACE, c(RankState.Immortal(2600), RankState.Immortal(2488)))
        assertEquals(Change.NONE, c(RankState.Immortal(2600), RankState.Immortal(2488), show = false)) // looks the same
        assertEquals(Change.OTHER, c(RankState.Immortal(null), RankState.Immortal(2488)))
        assertEquals(Change.OTHER, c(guardian4, RankState.Uncalibrated))
    }

    @Test fun `no change, no animation`() {
        assertTrue(frames(guardian4, guardian4).isEmpty())
        assertTrue(frames(RankState.Immortal(1), RankState.Immortal(2), show = false).isEmpty())
    }

    @Test fun `every animation starts on the old rank, ends on the new one, and stays on the leds`() {
        val cases = listOf(
            guardian3 to guardian4, guardian4 to guardian3, guardian4 to crusader1, crusader1 to guardian4,
            RankState.Uncalibrated to guardian3, divine5 to RankState.Immortal(2488),
            RankState.Immortal(2488) to divine5, RankState.Immortal(2600) to RankState.Immortal(2488),
            RankState.Immortal(null) to RankState.Immortal(2488), guardian4 to RankState.Uncalibrated,
            RankState.Immortal(999) to RankState.Immortal(1000), ranked(Medal.HERALD, 0) to ranked(Medal.HERALD, 1),
        )
        for ((old, new) in cases) {
            val label = "$old -> $new"
            val f = frames(old, new)
            assertArrayEquals(label, renderer.render(old, medals, true), f.first().pixels)
            assertArrayEquals(label, renderer.render(new, medals, true), f.last().pixels)
            val total = f.sumOf { it.durationMs }
            assertTrue("$label takes $total ms", total in 1_000..4_000)
            for (frame in f) {
                assertEquals(625, frame.pixels.size)
                for (i in frame.pixels.indices) {
                    val v = frame.pixels[i]
                    assertTrue(label, v in 0..MatrixLayout.MAX_BRIGHTNESS)
                    if (!MatrixLayout.isLed(i % 25, i / 25)) assertEquals(label, 0, v)
                }
            }
        }
    }

    @Test fun `new star fades in and blinks, the others stay lit`() {
        val f = frames(guardian3, guardian4).map { it.pixels }
        // Pips are centred, so they move when a star is added; the new one is the rightmost (index 3).
        val newPip = (0 until 625).single { pipIndex(guardian4, it) == 3 }
        val oldPips = (0 until 625).filter { pipIndex(guardian4, it) in 0..2 }
        val levels = f.map { it[newPip] }
        assertTrue(f.drop(1).all { frame -> oldPips.all { frame[it] == MatrixLayout.MAX_BRIGHTNESS } })
        assertEquals(0, levels[1])
        assertTrue("fades in: $levels", levels.subList(1, 8).zipWithNext().all { (a, b) -> b > a })
        assertEquals("blinks twice", 2, levels.zipWithNext().count { (a, b) -> a > 0 && b == 0 })
    }

    @Test fun `lost star fades out quietly`() {
        val f = frames(guardian4, guardian3)
        val start = f.first().pixels
        val lostPip = (0 until 625).single { pipIndex(guardian4, it) == 3 }
        val levels = f.dropLast(1).map { it.pixels[lostPip] }
        assertTrue("fades out: $levels", levels.zipWithNext().all { (a, b) -> b <= a })
        assertEquals(0, levels.last())
        // no flashes: nothing gets brighter than it was at the start or end
        val maxLit = f.maxOf { frame -> frame.pixels.count { it == MatrixLayout.MAX_BRIGHTNESS } }
        assertTrue(maxLit <= start.count { it == MatrixLayout.MAX_BRIGHTNESS })
    }

    @Test fun `tier up flashes the whole matrix once, then sparkles`() {
        val f = frames(guardian4, crusader1)
        val fullFrames = f.filter { frame -> (0 until 625).all { !MatrixLayout.isLed(it % 25, it / 25) || frame.pixels[it] == MatrixLayout.MAX_BRIGHTNESS } }
        assertEquals(1, fullFrames.size)
        val end = renderer.render(crusader1, medals, true)
        val sparkleFrames = f.takeLast(9).dropLast(1)
        assertTrue(sparkleFrames.all { frame -> frame.pixels.indices.count { frame.pixels[it] != end[it] } in 1..10 })
    }

    @Test fun `immortal place rolls through numbers to the new place`() {
        val noPlate = renderer.render(RankState.Immortal(null), medals, true)
        val all = frames(RankState.Immortal(2600), RankState.Immortal(2488))
        assertEquals("the new place blinks twice", 2, all.count { it.pixels.contentEquals(noPlate) })
        val f = all.filterNot { it.pixels.contentEquals(noPlate) }.map { it.pixels }
            .fold(listOf<IntArray>()) { acc, p -> if (acc.isNotEmpty() && acc.last().contentEquals(p)) acc else acc + p }
        val rolled = (2488..2600).map { renderer.render(RankState.Immortal(it), medals, true) }
        val shown = f.map { frame -> rolled.indexOfFirst { it.contentEquals(frame) } + 2488 }
        assertTrue("every frame shows a place: $shown", shown.all { it >= 2488 })
        assertEquals(2600, shown.first())
        assertEquals(2488, shown.last())
        assertTrue("counts down: $shown", shown.zipWithNext().all { (a, b) -> b < a })
        assertTrue(shown.size >= 8)
    }

    // --- helpers ---------------------------------------------------------------------------

    /** Which pip of [state] sits on LED [i], or -1: compares with the frame where only that pip is off. */
    private fun pipIndex(state: RankState.Ranked, i: Int): Int {
        for (p in 0 until state.stars) {
            val without = renderer.render(state, medals, true) { if (it == p) 0 else MatrixLayout.MAX_BRIGHTNESS }
            if (without[i] == 0 && renderer.render(state, medals, true)[i] > 0) return p
        }
        return -1
    }
}
