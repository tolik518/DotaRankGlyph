package com.glyphrank.dota.ui

import android.graphics.Color
import com.glyphrank.dota.glyph.MatrixLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The app preview and widget image of a frame, drawn with Android's real graphics. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MatrixPainterTest {
    private val size = 512
    /** Pixel centre of LED (x, y) in a [size] px bitmap (spec: 440 px matrix area, centred). */
    private fun ledCentre(i: Int) = ((size - 440) / 2f + (i + 0.5f) * 440f / MatrixLayout.SIZE).toInt()

    private fun frame(vararg lit: Pair<Pair<Int, Int>, Int>) = IntArray(MatrixLayout.SIZE * MatrixLayout.SIZE).also { f ->
        for ((xy, value) in lit) f[MatrixLayout.index(xy.first, xy.second)] = value
    }

    @Test fun `lit, dim and off leds, on a black disc with transparent corners`() {
        val bitmap = MatrixPainter.bitmap(frame((12 to 12) to MatrixLayout.MAX_BRIGHTNESS, (13 to 12) to 400), size)
        assertEquals(Color.WHITE, bitmap.getPixel(ledCentre(12), ledCentre(12)))
        val dim = Color.red(bitmap.getPixel(ledCentre(13), ledCentre(12)))
        assertEquals(0x1C, Color.red(bitmap.getPixel(ledCentre(11), ledCentre(12)))) // off: dark grey, not black
        assertTrue("dim $dim", dim in 0x1D..0xFE)
        assertEquals(Color.BLACK, bitmap.getPixel(size / 2, (size - 440) / 4)) // disc between the edge and the matrix
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(size - 1, size - 1))
    }

    @Test fun `corner positions outside the led circle stay dark even if set`() {
        val bitmap = MatrixPainter.bitmap(frame((0 to 0) to MatrixLayout.MAX_BRIGHTNESS), size)
        assertEquals(0, Color.alpha(bitmap.getPixel(ledCentre(0), ledCentre(0))))
    }
}
