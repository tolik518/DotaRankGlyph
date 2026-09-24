package com.glyphrank.dota.glyph

/** 3x5 bitmap font — just the characters this toy needs. */
object PixelFont {
    const val WIDTH = 3
    const val HEIGHT = 5
    const val SPACING = 1

    private val GLYPHS: Map<Char, Array<String>> = mapOf(
        '0' to arrayOf("###", "#.#", "#.#", "#.#", "###"),
        '1' to arrayOf(".#.", "##.", ".#.", ".#.", "###"),
        '2' to arrayOf("###", "..#", "###", "#..", "###"),
        '3' to arrayOf("###", "..#", ".##", "..#", "###"),
        '4' to arrayOf("#.#", "#.#", "###", "..#", "..#"),
        '5' to arrayOf("###", "#..", "###", "..#", "###"),
        '6' to arrayOf("###", "#..", "###", "#.#", "###"),
        '7' to arrayOf("###", "..#", ".#.", ".#.", ".#."),
        '8' to arrayOf("###", "#.#", "###", "#.#", "###"),
        '9' to arrayOf("###", "#.#", "###", "..#", "###"),
        '?' to arrayOf("###", "..#", ".##", "...", ".#."),
        '!' to arrayOf(".#.", ".#.", ".#.", "...", ".#."),
        'I' to arrayOf("###", ".#.", ".#.", ".#.", "###"),
        'D' to arrayOf("##.", "#.#", "#.#", "#.#", "##."),
        '-' to arrayOf("...", "...", "###", "...", "..."),
    )

    fun supports(c: Char) = c in GLYPHS

    fun textWidth(text: String, scale: Int = 1): Int =
        if (text.isEmpty()) 0 else (text.length * (WIDTH + SPACING) - SPACING) * scale

    /** Draws [text] horizontally centred on the matrix with its top edge at [top]. */
    fun drawCentered(canvas: MatrixCanvas, text: String, top: Int, value: Int, scale: Int = 1) {
        var x = (MatrixLayout.SIZE - textWidth(text, scale)) / 2
        for (c in text) {
            val rows = GLYPHS[c]
            if (rows != null) {
                for ((ry, row) in rows.withIndex()) for ((rx, bit) in row.withIndex()) {
                    if (bit != '#') continue
                    for (sy in 0 until scale) for (sx in 0 until scale) {
                        canvas.plot(x + rx * scale + sx, top + ry * scale + sy, value)
                    }
                }
            }
            x += (WIDTH + SPACING) * scale
        }
    }
}
