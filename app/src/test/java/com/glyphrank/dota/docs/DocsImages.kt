package com.glyphrank.dota.docs

import com.glyphrank.dota.glyph.AnimationFrame
import com.glyphrank.dota.glyph.MatrixLayout
import com.glyphrank.dota.glyph.MedalArt
import com.glyphrank.dota.glyph.RankAnimation
import com.glyphrank.dota.glyph.RankRenderer
import com.glyphrank.dota.rank.Medal
import com.glyphrank.dota.rank.RankState
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Regenerates the images in `docs/` from the real renderer and animations:
 *
 *     DOCS_OUT=$PWD/docs ./gradlew testDebugUnitTest --tests '*DocsImages*'
 *
 * Skipped in normal test runs (no `DOCS_OUT`). The Android unit-test classpath has no
 * java.awt, so this draws into a plain grey raster and writes PNG/GIF itself. LEDs look
 * like the app preview (`ui/MatrixPainter`): dark off-LEDs and a gamma curve for dim values.
 */
class DocsImages {
    private val out = System.getenv("DOCS_OUT")?.let(::File)
    private val medals = MedalArt.fromJson(File("src/main/assets/dota_rank_medals.json").readText())
    private val renderer = RankRenderer()

    /** All medals, Herald 1 … Divine 4 and Immortal #2488, in a 4×2 grid. */
    @Test fun `medal grid`() {
        assumeTrue("set DOCS_OUT to write the docs images", out != null)
        val states = listOf(
            RankState.Ranked(Medal.HERALD, 1), RankState.Ranked(Medal.GUARDIAN, 2),
            RankState.Ranked(Medal.CRUSADER, 3), RankState.Ranked(Medal.ARCHON, 4),
            RankState.Ranked(Medal.LEGEND, 5), RankState.Ranked(Medal.ANCIENT, 2),
            RankState.Ranked(Medal.DIVINE, 4), RankState.Immortal(2488),
        )
        val cols = 4
        val cell = 280
        val side = 250
        val image = Raster(cols * cell, (states.size / cols) * cell)
        states.forEachIndexed { i, state ->
            val left = (i % cols) * cell + (cell - side) / 2
            val top = (i / cols) * cell + (cell - side) / 2
            image.drawMatrix(renderer.render(state, medals, true), left, top, side)
        }
        writePng(image, File(out, "glyph-medals.png"))
    }

    /** Guardian 4 → 5 (a new star), then Guardian 5 → Crusader 1 (a new medal). */
    @Test fun `rank change animation`() {
        assumeTrue("set DOCS_OUT to write the docs images", out != null)
        val animation = RankAnimation(renderer)
        val guardian4 = RankState.Ranked(Medal.GUARDIAN, 4)
        val guardian5 = RankState.Ranked(Medal.GUARDIAN, 5)
        val crusader1 = RankState.Ranked(Medal.CRUSADER, 1)
        fun hold(state: RankState, ms: Long) = AnimationFrame(renderer.render(state, medals, true), ms)
        val frames = listOf(hold(guardian4, 800)) +
            animation.frames(guardian4, guardian5, medals, true) +
            listOf(hold(guardian5, 900)) +
            animation.frames(guardian5, crusader1, medals, true) +
            listOf(hold(crusader1, 1600))
        val size = 240
        writeGif(
            frames.map { frame -> Raster(size, size).apply { drawMatrix(frame.pixels, 0, 0, size) } to frame.durationMs },
            File(out, "rank-change.gif"),
        )
    }

    // --- raster --------------------------------------------------------------------------

    /** Grey image, one byte per pixel, black background. */
    private class Raster(val width: Int, val height: Int) {
        val pixels = ByteArray(width * height)

        fun fillRect(x0: Int, y0: Int, w: Int, h: Int, gray: Int, round: Boolean) {
            for (y in y0 until y0 + h) for (x in x0 until x0 + w) {
                if (x !in 0 until width || y !in 0 until height) continue
                val corner = round && (x == x0 || x == x0 + w - 1) && (y == y0 || y == y0 + h - 1)
                if (!corner) pixels[y * width + x] = gray.toByte()
            }
        }

        /** Same proportions as MatrixPainter: 440/512 of [side] is the LED area. */
        fun drawMatrix(frame: IntArray, left: Int, top: Int, side: Int) {
            val area = side * 440.0 / 512.0
            val pitch = area / MatrixLayout.SIZE
            val led = (pitch * 0.74).roundToInt()
            val origin = (side - area) / 2
            for (y in 0 until MatrixLayout.SIZE) for (x in 0 until MatrixLayout.SIZE) {
                if (!MatrixLayout.isLed(x, y)) continue
                val v = frame[MatrixLayout.index(x, y)].toDouble() / MatrixLayout.MAX_BRIGHTNESS
                val gray = if (v <= 0) OFF_GRAY else (OFF_GRAY + (255 - OFF_GRAY) * v.pow(1 / 2.2)).roundToInt()
                val px = (left + origin + x * pitch + (pitch - led) / 2).roundToInt()
                val py = (top + origin + y * pitch + (pitch - led) / 2).roundToInt()
                fillRect(px, py, led, led, gray, round = led >= 6)
            }
        }
    }

    // --- PNG (8-bit greyscale) ---------------------------------------------------------------

    private fun writePng(image: Raster, file: File) {
        val raw = ByteArrayOutputStream()
        DeflaterOutputStream(raw).use { z ->
            for (y in 0 until image.height) {
                z.write(0) // filter: none
                z.write(image.pixels, y * image.width, image.width)
            }
        }
        DataOutputStream(file.outputStream().buffered()).use { png ->
            png.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            val header = ByteArrayOutputStream().also {
                DataOutputStream(it).apply {
                    writeInt(image.width); writeInt(image.height)
                    writeByte(8); writeByte(0); writeByte(0); writeByte(0); writeByte(0) // 8-bit grey
                }
            }.toByteArray()
            png.chunk("IHDR", header)
            png.chunk("IDAT", raw.toByteArray())
            png.chunk("IEND", ByteArray(0))
        }
    }

    private fun DataOutputStream.chunk(type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        writeInt(data.size)
        write(typeBytes)
        write(data)
        writeInt(CRC32().apply { update(typeBytes); update(data) }.value.toInt())
    }

    // --- GIF (looping, 256 greys) ----------------------------------------------------------

    private fun writeGif(frames: List<Pair<Raster, Long>>, file: File) {
        val width = frames.first().first.width
        val height = frames.first().first.height
        file.outputStream().buffered().use { gif ->
            fun le16(v: Int) { gif.write(v and 0xFF); gif.write(v shr 8 and 0xFF) }
            gif.write("GIF89a".toByteArray(Charsets.US_ASCII))
            le16(width); le16(height)
            gif.write(0xF7); gif.write(0); gif.write(0) // global colour table, 256 entries
            for (i in 0 until 256) repeat(3) { gif.write(i) } // the greys
            // Loop forever (NETSCAPE2.0 extension).
            gif.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B) + "NETSCAPE2.0".toByteArray(Charsets.US_ASCII) +
                byteArrayOf(3, 1, 0, 0, 0))
            for ((image, durationMs) in frames) {
                val delay = (durationMs / 10).toInt().coerceAtLeast(2) // hundredths of a second
                gif.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0x04)) // graphic control: keep the frame
                le16(delay); gif.write(0); gif.write(0)
                gif.write(0x2C); le16(0); le16(0); le16(width); le16(height); gif.write(0)
                gif.write(8) // LZW minimum code size
                val data = lzw(image.pixels)
                var i = 0
                while (i < data.size) {
                    val n = minOf(255, data.size - i)
                    gif.write(n); gif.write(data, i, n)
                    i += n
                }
                gif.write(0)
            }
            gif.write(0x3B)
        }
    }

    /** GIF-flavoured LZW (LSB-first codes), following Go's compress/lzw writer. */
    private fun lzw(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var bits = 0L
        var nBits = 0
        var width = 9
        val clear = 256
        val eof = 257
        var hi = eof
        var overflow = 512
        val table = HashMap<Int, Int>()
        fun write(code: Int) {
            bits = bits or (code.toLong() shl nBits)
            nBits += width
            while (nBits >= 8) {
                out.write((bits and 0xFF).toInt())
                bits = bits ushr 8
                nBits -= 8
            }
        }
        /** Next code; resets the table when all 12-bit codes are used. Returns false after a reset. */
        fun incHi(): Boolean {
            hi++
            if (hi == overflow) {
                width++
                overflow = overflow shl 1
            }
            if (hi == 4095) {
                write(clear)
                width = 9
                hi = eof
                overflow = 512
                table.clear()
                return false
            }
            return true
        }
        write(clear)
        var saved = input[0].toInt() and 0xFF
        for (i in 1 until input.size) {
            val x = input[i].toInt() and 0xFF
            val key = saved shl 8 or x
            val found = table[key]
            if (found != null) {
                saved = found
                continue
            }
            write(saved)
            saved = x
            if (incHi()) table[key] = hi
        }
        write(saved)
        incHi()
        write(eof)
        if (nBits > 0) out.write((bits and 0xFF).toInt())
        return out.toByteArray()
    }

    private companion object {
        const val OFF_GRAY = 0x1C
    }
}
