package com.glyphrank.dota.ui

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.glyphrank.dota.glyph.MatrixLayout
import kotlin.math.min
import kotlin.math.roundToInt

/** In-app preview of a Glyph Matrix frame, see [MatrixPainter]. */
class MatrixPreviewView(context: Context) : View(context) {

    var frame: IntArray = IntArray(MatrixLayout.SIZE * MatrixLayout.SIZE)
        set(value) {
            field = value
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val cap = (320 * resources.displayMetrics.density).roundToInt()
        val side = min(w, cap)
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        val side = min(width, height).toFloat()
        MatrixPainter.draw(canvas, frame, (width - side) / 2f, (height - side) / 2f, side)
    }
}
