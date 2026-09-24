package com.glyphrank.dota.glyph

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Nothing Phone (3) Glyph Matrix geometry.
 *
 * Frames are 25x25 int arrays (index = y * 25 + x), but only 489 positions have
 * an LED: the rows form a circle. Row widths were read off Nothing's official
 * "Phone (3) Glyph Matrix LED allocation" diagram in the GlyphMatrix-Developer-Kit.
 *
 * Brightness: the SDK's own bitmap converter scales pixels to 0..4095, so that is
 * the full range of a raw frame value.
 */
object MatrixLayout {
    const val SIZE = 25
    const val CENTER = 12
    const val MAX_BRIGHTNESS = 4095

    private val ROW_WIDTHS = intArrayOf(
        7, 11, 15, 17, 19, 21, 21, 23, 23,
        25, 25, 25, 25, 25, 25, 25,
        23, 23, 21, 21, 19, 17, 15, 11, 7,
    )

    val LED_COUNT: Int = ROW_WIDTHS.sum()

    fun isLed(x: Int, y: Int): Boolean {
        if (y !in 0 until SIZE || x !in 0 until SIZE) return false
        val start = (SIZE - ROW_WIDTHS[y]) / 2
        return x >= start && x < start + ROW_WIDTHS[y]
    }

    fun index(x: Int, y: Int) = y * SIZE + x

    /** Distance of an LED from the matrix centre. */
    fun radius(x: Int, y: Int): Double = hypot((x - CENTER).toDouble(), (y - CENTER).toDouble())

    /** Clockwise angle in degrees, 0 = straight up (12 o'clock). */
    fun angle(x: Int, y: Int): Double {
        val deg = Math.toDegrees(atan2((x - CENTER).toDouble(), (CENTER - y).toDouble()))
        return if (deg < 0) deg + 360.0 else deg
    }
}

/** Mutable frame. Writes outside the LED circle are ignored. */
class MatrixCanvas {
    val pixels = IntArray(MatrixLayout.SIZE * MatrixLayout.SIZE)

    operator fun get(x: Int, y: Int): Int =
        if (MatrixLayout.isLed(x, y)) pixels[MatrixLayout.index(x, y)] else 0

    /** Sets a pixel, keeping the brighter value if something is already drawn there. */
    fun plot(x: Int, y: Int, value: Int) {
        if (!MatrixLayout.isLed(x, y)) return
        val i = MatrixLayout.index(x, y)
        pixels[i] = maxOf(pixels[i], value.coerceIn(0, MatrixLayout.MAX_BRIGHTNESS))
    }

    /** Overwrites a pixel (use 0 to punch a hole into existing art). */
    operator fun set(x: Int, y: Int, value: Int) {
        if (!MatrixLayout.isLed(x, y)) return
        pixels[MatrixLayout.index(x, y)] = value.coerceIn(0, MatrixLayout.MAX_BRIGHTNESS)
    }

    inline fun forEachLed(action: (x: Int, y: Int) -> Unit) {
        for (y in 0 until MatrixLayout.SIZE) for (x in 0 until MatrixLayout.SIZE) {
            if (MatrixLayout.isLed(x, y)) action(x, y)
        }
    }

    fun toArray(): IntArray = pixels.copyOf()
}
