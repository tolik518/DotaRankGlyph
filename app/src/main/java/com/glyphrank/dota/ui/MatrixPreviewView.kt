package com.glyphrank.dota.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.glyphrank.dota.glyph.MatrixLayout
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** In-app preview of a Glyph Matrix frame (colours follow Nothing's preview spec). */
class MatrixPreviewView(context: Context) : View(context) {

    var frame: IntArray = IntArray(MatrixLayout.SIZE * MatrixLayout.SIZE)
        set(value) {
            field = value
            invalidate()
        }

    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val led = Paint()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val cap = (320 * resources.displayMetrics.density).roundToInt()
        val side = min(w, cap)
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        val side = min(width, height).toFloat()
        val left = (width - side) / 2f
        val top = (height - side) / 2f
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

    private companion object {
        const val OFF_GRAY = 0x1C
    }
}
