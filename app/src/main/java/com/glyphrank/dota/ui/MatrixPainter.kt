package com.glyphrank.dota.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.glyphrank.dota.glyph.MatrixLayout
import kotlin.math.pow
import kotlin.math.roundToInt

/** Draws a Glyph Matrix frame the way it looks on the phone (colours follow Nothing's preview spec). Main thread only. */
object MatrixPainter {
    private const val OFF_GRAY = 0x1C
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val led = Paint()

    /** Draws [frame] as a black disc of LEDs in the square at ([left], [top]) with side [side]. */
    fun draw(canvas: Canvas, frame: IntArray, left: Float, top: Float, side: Float) {
        canvas.drawCircle(left + side / 2f, top + side / 2f, side / 2f, background)

        // Spec: 512px canvas, 440px matrix area, LEDs ~13/17.6 of the pitch.
        val area = side * 440f / 512f
        val pitch = area / MatrixLayout.SIZE
        val ledSize = pitch * 0.74f
        val origin = (side - area) / 2f
        for (y in 0 until MatrixLayout.SIZE) for (x in 0 until MatrixLayout.SIZE) {
            if (!MatrixLayout.isLed(x, y)) continue
            val v = frame[MatrixLayout.index(x, y)].toDouble() / MatrixLayout.MAX_BRIGHTNESS
            // Rough perceptual curve so dim LEDs look like they do on the phone.
            val g = if (v <= 0) OFF_GRAY else (OFF_GRAY + (255 - OFF_GRAY) * v.pow(1 / 2.2)).roundToInt()
            led.color = Color.rgb(g, g, g)
            val px = left + origin + x * pitch + (pitch - ledSize) / 2f
            val py = top + origin + y * pitch + (pitch - ledSize) / 2f
            canvas.drawRect(px, py, px + ledSize, py + ledSize, led)
        }
    }

    /** [frame] as a square bitmap with a transparent corner area. */
    fun bitmap(frame: IntArray, sizePx: Int): Bitmap =
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also {
            draw(Canvas(it), frame, 0f, 0f, sizePx.toFloat())
        }
}
