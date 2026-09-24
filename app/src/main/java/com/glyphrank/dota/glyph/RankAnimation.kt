package com.glyphrank.dota.glyph

import com.glyphrank.dota.rank.RankState
import kotlin.math.roundToInt

/** One frame of an animation and how long it stays up. */
class AnimationFrame(val pixels: IntArray, val durationMs: Long)

/**
 * The animation played when a refresh brings a different rank. Starts on the old rank,
 * ends on the new one (exactly what [RankRenderer.render] draws for it):
 *
 *  - [Change.STAR_UP]:    the new star pip fades in, then blinks twice.
 *  - [Change.TIER_UP]:    a light wave washes over the old medal and uncovers the new one,
 *                         then sparkles (also for the first calibration and reaching Immortal).
 *  - [Change.STAR_DOWN]:  the lost star pip quietly fades out.
 *  - [Change.TIER_DOWN]:  the old medal fades into the new one (also for any other change).
 *  - [Change.PLACE]:      the Immortal leaderboard place rolls to the new number, which blinks twice.
 */
class RankAnimation(
    private val renderer: RankRenderer = RankRenderer(),
    private val full: Int = MatrixLayout.MAX_BRIGHTNESS,
) {
    enum class Change { NONE, STAR_UP, STAR_DOWN, TIER_UP, TIER_DOWN, PLACE, OTHER }

    fun frames(old: RankState, new: RankState, medals: MedalArt?, showImmortalRank: Boolean): List<AnimationFrame> {
        val from = renderer.render(old, medals, showImmortalRank)
        val to = renderer.render(new, medals, showImmortalRank)
        return when (classify(old, new, showImmortalRank)) {
            Change.NONE -> emptyList()
            Change.STAR_UP -> starUp(old as RankState.Ranked, new as RankState.Ranked, from, medals, showImmortalRank)
            Change.STAR_DOWN -> starDown(old as RankState.Ranked, new as RankState.Ranked, from, to, medals, showImmortalRank)
            Change.TIER_UP -> tierUp(from, to)
            Change.PLACE -> placeRoll(old as RankState.Immortal, new as RankState.Immortal, from, to, medals)
            Change.TIER_DOWN, Change.OTHER -> crossFade(from, to)
        }
    }

    // --- the animations --------------------------------------------------------------

    private fun starUp(
        old: RankState.Ranked, new: RankState.Ranked, from: IntArray, medals: MedalArt?, show: Boolean,
    ): List<AnimationFrame> {
        val isNew = { i: Int -> i >= old.stars }
        fun withNewPips(level: Int) = renderer.render(new, medals, show) { i -> if (isNew(i)) level else full }
        return buildList {
            add(AnimationFrame(from, HOLD_MS))
            add(AnimationFrame(withNewPips(0), 200))
            for (k in 1..FADE_STEPS) add(AnimationFrame(withNewPips(full * k / FADE_STEPS), 70))
            repeat(2) {
                add(AnimationFrame(withNewPips(0), 140))
                add(AnimationFrame(withNewPips(full), 140))
            }
            add(AnimationFrame(renderer.render(new, medals, show), END_MS))
        }
    }

    private fun starDown(
        old: RankState.Ranked, new: RankState.Ranked, from: IntArray, to: IntArray, medals: MedalArt?, show: Boolean,
    ): List<AnimationFrame> = buildList {
        add(AnimationFrame(from, HOLD_MS))
        for (k in FADE_STEPS - 1 downTo 0) {
            val level = full * k / FADE_STEPS
            add(AnimationFrame(renderer.render(old, medals, show) { i -> if (i >= new.stars) level else full }, 110))
        }
        add(AnimationFrame(to, END_MS))
    }

    private fun tierUp(from: IntArray, to: IntArray): List<AnimationFrame> = buildList {
        add(AnimationFrame(from, HOLD_MS))
        // A disc of light grows from the centre over the old medal …
        for (k in 1 until WAVE_STEPS) add(AnimationFrame(wave(from, radius = k * WAVE_STEP, inside = true), 45))
        add(AnimationFrame(wave(from, radius = 99.0, inside = true), 90))
        // … and shrinks outwards, uncovering the new one.
        for (k in 1 until WAVE_STEPS) add(AnimationFrame(wave(to, radius = k * WAVE_STEP, inside = false), 45))
        for (k in 0 until SPARKLE_FRAMES) add(AnimationFrame(sparkle(to, seed = k), 110))
        add(AnimationFrame(to, END_MS))
    }

    private fun placeRoll(
        old: RankState.Immortal, new: RankState.Immortal, from: IntArray, to: IntArray, medals: MedalArt?,
    ): List<AnimationFrame> {
        val a = old.leaderboardRank ?: return crossFade(from, to)
        val b = new.leaderboardRank ?: return crossFade(from, to)
        return buildList {
            add(AnimationFrame(from, HOLD_MS))
            var last = a
            for (k in 1 until ROLL_STEPS) {
                val t = k.toDouble() / ROLL_STEPS
                val eased = 1 - (1 - t) * (1 - t) // fast start, slow finish
                val n = (a + (b - a) * eased).roundToInt()
                if (n == last || n == b) continue
                last = n
                add(AnimationFrame(renderer.render(RankState.Immortal(n), medals, true), 80))
            }
            val noPlate = renderer.render(RankState.Immortal(null), medals, true)
            repeat(2) {
                add(AnimationFrame(to, 180))
                add(AnimationFrame(noPlate, 150))
            }
            add(AnimationFrame(to, END_MS))
        }
    }

    private fun crossFade(from: IntArray, to: IntArray): List<AnimationFrame> = buildList {
        add(AnimationFrame(from, HOLD_MS))
        for (k in 1 until CROSSFADE_STEPS) {
            val t = k.toDouble() / CROSSFADE_STEPS
            add(AnimationFrame(IntArray(from.size) { i -> (from[i] * (1 - t) + to[i] * t).roundToInt() }, 80))
        }
        add(AnimationFrame(to, END_MS))
    }

    // --- helpers -------------------------------------------------------------------------

    /** [base] with the LEDs inside (or outside) [radius] at full brightness. */
    private fun wave(base: IntArray, radius: Double, inside: Boolean) = MatrixCanvas().apply {
        forEachLed { x, y ->
            val lit = (MatrixLayout.radius(x, y) <= radius) == inside
            this[x, y] = if (lit) full else base[MatrixLayout.index(x, y)]
        }
    }.toArray()

    /** [base] plus a few bright LEDs on its dark parts, a different set for every [seed]. */
    private fun sparkle(base: IntArray, seed: Int) = MatrixCanvas().apply {
        base.copyInto(pixels)
        var state = SPARKLE_SEED + seed * 7919
        var placed = 0
        var tries = 0
        while (placed < SPARKLES && tries++ < 500) {
            state = state * 1103515245 + 12345
            val i = Math.floorMod(state ushr 8, MatrixLayout.SIZE * MatrixLayout.SIZE)
            val x = i % MatrixLayout.SIZE
            val y = i / MatrixLayout.SIZE
            if (!MatrixLayout.isLed(x, y) || base[i] > full / 4) continue
            this[x, y] = full
            placed++
        }
    }.toArray()

    companion object {
        private const val HOLD_MS = 400L
        private const val END_MS = 300L
        private const val FADE_STEPS = 6
        private const val WAVE_STEPS = 7
        private const val WAVE_STEP = 13.0 / WAVE_STEPS
        private const val SPARKLE_FRAMES = 8
        private const val SPARKLES = 10
        private const val SPARKLE_SEED = 20_250_925
        private const val ROLL_STEPS = 14
        private const val CROSSFADE_STEPS = 8

        fun classify(old: RankState, new: RankState, showImmortalRank: Boolean): Change = when {
            old == new -> Change.NONE
            old is RankState.Ranked && new is RankState.Ranked -> when {
                new.medal > old.medal -> Change.TIER_UP
                new.medal < old.medal -> Change.TIER_DOWN
                new.stars > old.stars -> Change.STAR_UP
                else -> Change.STAR_DOWN
            }
            old is RankState.Immortal && new is RankState.Immortal ->
                if (showImmortalRank && old.leaderboardRank != null && new.leaderboardRank != null) Change.PLACE
                else if (showImmortalRank) Change.OTHER // place appeared or vanished
                else Change.NONE // looks the same
            new is RankState.Immortal -> Change.TIER_UP // from Divine (or first calibration)
            old == RankState.Uncalibrated -> Change.TIER_UP // first calibration
            old is RankState.Immortal -> Change.TIER_DOWN
            else -> Change.OTHER // e.g. back to uncalibrated
        }
    }
}
