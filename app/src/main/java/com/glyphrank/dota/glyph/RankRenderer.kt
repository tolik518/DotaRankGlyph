package com.glyphrank.dota.glyph

import com.glyphrank.dota.rank.Medal
import com.glyphrank.dota.rank.RankState
import com.glyphrank.dota.rank.RankTier
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws an original rank emblem for the 25x25 Glyph Matrix:
 *
 *  - Outer ring: 8 arcs, one per medal tier. Arcs 1..tier are lit, the rest are dim
 *    "slots", so Guardian = 2 of 8 lit, Immortal = all 8.
 *  - Centre: the stars as a constellation of small crosses on an orbit
 *    (1 = single star on top, 3 = triangle, 4 = square, 5 = pentagon ...),
 *    with empty star slots shown dim.
 *  - Immortal: all arcs lit, leaderboard position in the centre (if known).
 */
class RankRenderer(
    private val full: Int = MatrixLayout.MAX_BRIGHTNESS,
    private val dim: Int = 480,
) {
    /**
     * Renders [state]. With an [iconPack], Herald..Immortal use the pack's icon for that
     * medal (stars overlaid on the top arc); tiers missing from the pack, and status
     * screens, use the built-in emblem.
     */
    fun render(state: RankState, iconPack: IconPack?): IntArray {
        val icon = when (state) {
            is RankState.Ranked -> iconPack?.iconFor(state.medal)
            is RankState.Immortal -> iconPack?.iconFor(Medal.IMMORTAL)
            RankState.Uncalibrated -> null
        } ?: return render(state)
        return MatrixCanvas().apply {
            icon.copyInto(pixels)
            if (state is RankState.Ranked) drawStarPips(state.stars)
        }.toArray()
    }

    fun render(state: RankState): IntArray = when (state) {
        RankState.Uncalibrated -> message("?", scale = 2)
        is RankState.Ranked -> MatrixCanvas().apply {
            drawRing(litArcs = state.medal.tierNumber)
            drawStars(state.stars)
        }.toArray()
        is RankState.Immortal -> MatrixCanvas().apply {
            drawRing(litArcs = ARCS)
            val lb = state.leaderboardRank
            if (lb != null && lb in 1..9999) {
                PixelFont.drawCentered(this, lb.toString(), top = 10, value = full)
            } else {
                drawStar(MatrixLayout.CENTER, MatrixLayout.CENTER, full, big = true)
            }
        }.toArray()
    }

    /** Dim ring with a short text in the middle, e.g. "ID" (no account set) or "!" (error). */
    fun message(text: String, scale: Int = if (text.length <= 1) 2 else 1): IntArray =
        MatrixCanvas().apply {
            drawRing(litArcs = 0)
            val top = MatrixLayout.CENTER - (PixelFont.HEIGHT * scale) / 2
            PixelFont.drawCentered(this, text, top = top, value = full, scale = scale)
        }.toArray()

    /** One frame of a spinner that runs around the ring; [frame] just keeps counting up. */
    fun loading(frame: Int): IntArray = MatrixCanvas().apply {
        val head = (frame * SPINNER_STEP_DEG) % 360.0
        forEachLed { x, y ->
            if (!isRing(x, y)) return@forEachLed
            val behind = (head - MatrixLayout.angle(x, y) + 360.0) % 360.0
            val v = if (behind <= SPINNER_TAIL_DEG) {
                (full * (1.0 - behind / SPINNER_TAIL_DEG)).roundToInt().coerceAtLeast(dim)
            } else {
                dim
            }
            plot(x, y, v)
        }
    }.toArray()

    /**
     * One frame of [frame] shaking sideways, shown while the last known rank is being
     * reloaded; [step] just keeps counting up (one step per [SHAKE_FRAME_MS]).
     */
    fun shake(frame: IntArray, step: Int): IntArray = MatrixCanvas().apply {
        val dx = SHAKE_OFFSETS[Math.floorMod(step, SHAKE_OFFSETS.size)]
        forEachLed { x, y ->
            val sx = x - dx
            if (sx in 0 until MatrixLayout.SIZE) this[x, y] = frame[MatrixLayout.index(sx, y)]
        }
    }.toArray()

    // --- drawing helpers -------------------------------------------------------

    private fun MatrixCanvas.drawRing(litArcs: Int) = forEachLed { x, y ->
        val arc = arcAt(x, y) ?: return@forEachLed
        plot(x, y, if (arc < litArcs) full else dim)
    }

    /** Arc index 0..7 (clockwise from 12 o'clock) for ring LEDs, null for gaps and the inner area. */
    internal fun arcAt(x: Int, y: Int): Int? {
        if (!MatrixLayout.isLed(x, y) || !isRing(x, y)) return null
        val a = MatrixLayout.angle(x, y)
        val within = a % ARC_DEG
        if (within < ARC_GAP_DEG || within > ARC_DEG - ARC_GAP_DEG) return null
        return (a / ARC_DEG).toInt().coerceIn(0, ARCS - 1)
    }

    private fun MatrixCanvas.drawStars(stars: Int) {
        val slots = if (stars > RankTier.CURRENT_STAR_SLOTS) RankTier.MAX_STARS else RankTier.CURRENT_STAR_SLOTS
        for (i in 0 until slots) {
            val theta = Math.toRadians(360.0 * i / slots)
            val cx = (MatrixLayout.CENTER + STAR_ORBIT * sin(theta)).roundToInt()
            val cy = (MatrixLayout.CENTER - STAR_ORBIT * cos(theta)).roundToInt()
            drawStar(cx, cy, if (i < stars) full else dim)
        }
    }

    /**
     * Earned stars as single bright LEDs on a dark band cut into the top edge of the art,
     * so they read cleanly over any icon.
     */
    private fun MatrixCanvas.drawStarPips(stars: Int) {
        if (stars <= 0) return
        val spacing = if (stars > RankTier.CURRENT_STAR_SLOTS) PIP_SPACING_DENSE_DEG else PIP_SPACING_DEG
        val halfSpan = (stars - 1) / 2.0 * spacing
        val cut = BooleanArray(pixels.size)
        forEachLed { x, y ->
            if (MatrixLayout.radius(x, y) < BAND_INNER_RADIUS) return@forEachLed
            val a = MatrixLayout.angle(x, y).let { if (it > 180.0) it - 360.0 else it }
            if (abs(a) <= halfSpan + BAND_MARGIN_DEG) {
                if (this[x, y] > 0) cut[MatrixLayout.index(x, y)] = true
                this[x, y] = 0
            }
        }
        removeCutOffFragments(cut)
        for (i in 0 until stars) {
            val theta = Math.toRadians((i - (stars - 1) / 2.0) * spacing)
            val px = (MatrixLayout.CENTER + PIP_RADIUS * sin(theta)).roundToInt()
            val py = (MatrixLayout.CENTER - PIP_RADIUS * cos(theta)).roundToInt()
            this[px, py] = full
        }
    }

    /**
     * Clears tiny pieces of art that the band sliced off the rest of the icon
     * ([cut] = art LEDs the band switched off). On the matrix a lone lit LED next to
     * the band is indistinguishable from a star pip.
     */
    private fun MatrixCanvas.removeCutOffFragments(cut: BooleanArray) {
        val seen = BooleanArray(pixels.size)
        forEachLed { x, y ->
            val start = MatrixLayout.index(x, y)
            if (pixels[start] == 0 || seen[start]) return@forEachLed
            seen[start] = true
            val fragment = mutableListOf(start)
            var touchesCut = false
            var i = 0
            while (i < fragment.size) {
                val p = fragment[i++]
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = p % MatrixLayout.SIZE + dx
                    val ny = p / MatrixLayout.SIZE + dy
                    if (!MatrixLayout.isLed(nx, ny)) continue
                    val n = MatrixLayout.index(nx, ny)
                    if (cut[n]) touchesCut = true
                    if (pixels[n] > 0 && !seen[n]) {
                        seen[n] = true
                        fragment += n
                    }
                }
            }
            if (touchesCut && fragment.size <= MAX_CUT_OFF_FRAGMENT) fragment.forEach { pixels[it] = 0 }
        }
    }

    private fun MatrixCanvas.drawStar(cx: Int, cy: Int, value: Int, big: Boolean = false) {
        val arm = if (big) 3 else 1
        plot(cx, cy, value)
        for (d in 1..arm) {
            val v = if (big && d == arm) value / 2 else value
            plot(cx + d, cy, v); plot(cx - d, cy, v)
            plot(cx, cy + d, v); plot(cx, cy - d, v)
        }
        if (big) {
            val diag = value / 2
            plot(cx + 1, cy + 1, diag); plot(cx - 1, cy - 1, diag)
            plot(cx + 1, cy - 1, diag); plot(cx - 1, cy + 1, diag)
        }
    }

    private fun isRing(x: Int, y: Int) = MatrixLayout.radius(x, y) >= RING_INNER_RADIUS

    companion object {
        const val ARCS = 8
        const val SHAKE_FRAME_MS = 60L
        /** Sideways offsets of one shake cycle; it ends centred so it can stop after any whole cycle. */
        private val SHAKE_OFFSETS = intArrayOf(1, 0, -1, 0)
        val SHAKE_CYCLE_STEPS: Int = SHAKE_OFFSETS.size
        private const val ARC_DEG = 360.0 / ARCS
        private const val ARC_GAP_DEG = 5.5
        private const val RING_INNER_RADIUS = 10.8
        private const val STAR_ORBIT = 5.0
        private const val PIP_RADIUS = 11.0
        private const val BAND_INNER_RADIUS = 9.0
        private const val BAND_MARGIN_DEG = 12.0
        private const val PIP_SPACING_DEG = 17.0
        private const val PIP_SPACING_DENSE_DEG = 13.0
        private const val MAX_CUT_OFF_FRAGMENT = 2
        private const val SPINNER_STEP_DEG = 15.0
        private const val SPINNER_TAIL_DEG = 120.0

        init {
            check(Medal.entries.size == ARCS) { "One ring arc per medal tier" }
        }
    }
}
