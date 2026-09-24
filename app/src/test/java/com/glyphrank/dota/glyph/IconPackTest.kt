package com.glyphrank.dota.glyph

import com.glyphrank.dota.rank.Medal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class IconPackTest {
    /** Fake image decoder: a "PNG" here is one byte v -> a flat 25x25 image of grey v. */
    private val fakeDecode: (ByteArray) -> IntArray? = { b -> if (b.size == 1) IntArray(625) { b[0].toInt() and 0xFF } else null }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    private fun bitmapsJson(vararg medals: Pair<String, Int>): ByteArray {
        val ranks = medals.joinToString(",") { (name, v) ->
            val row = List(25) { v }.joinToString(",", "[", "]")
            "\"$name\":" + List(25) { row }.joinToString(",", "[", "]")
        }
        return """{"size":[25,25],"ranks":{$ranks}}""".toByteArray()
    }

    private fun png(v: Int) = byteArrayOf(v.toByte())

    @Test fun `file names map to medals`() {
        assertEquals(Medal.HERALD, IconPackParser.medalForFileName("dota2_glyph_medals/herald.png"))
        assertEquals(Medal.DIVINE, IconPackParser.medalForFileName("Medal_Divine.PNG"))
        assertEquals(Medal.IMMORTAL, IconPackParser.medalForFileName("immortal.webp"))
        assertNull(IconPackParser.medalForFileName("preview.png"))
        assertNull(IconPackParser.medalForFileName("README.txt"))
    }

    @Test fun `grey 0-255 scales to led range 0-4095`() {
        assertEquals(listOf(0, 2056, 4095), IconPackParser.gray255ToFrame(intArrayOf(0, 128, 255)).toList())
    }

    @Test fun `bitmaps json with malformed entries keeps the valid ones`() {
        val json = """{"ranks":{"herald":${List(25) { List(25) { 255 } }},"guardian":[[1,2,3]]}}"""
        val icons = IconPackParser.fromBitmapsJson(json)
        assertEquals(setOf(Medal.HERALD), icons.keys)
        assertEquals(4095, icons.getValue(Medal.HERALD)[12 * 25 + 12])
    }

    @Test fun `zip with json, images and extra files - json wins, extras ignored`() {
        val pack = IconPackParser.fromZip(
            zip(
                "pack/README.txt" to "hello".toByteArray(),
                "pack/preview.png" to png(99),
                "pack/herald.png" to png(10),
                "pack/crusader.png" to png(30),
                "pack/bitmaps.json" to bitmapsJson("herald" to 255, "guardian" to 128),
            ),
            fakeDecode,
        )
        assertEquals(setOf(Medal.HERALD, Medal.GUARDIAN, Medal.CRUSADER), pack.icons.keys)
        assertEquals(4095, pack.iconFor(Medal.HERALD)!![12 * 25 + 12]) // from JSON, not the png
        assertEquals(IconPackParser.gray255ToFrame(intArrayOf(30))[0], pack.iconFor(Medal.CRUSADER)!![12 * 25 + 12])
    }

    @Test fun `images only`() {
        val entries = Medal.entries.map { "${it.fileKey}.png" to png(200) }.toTypedArray()
        assertEquals(8, IconPackParser.fromZip(zip(*entries), fakeDecode).size)
    }

    @Test fun `undecodable or oversized images are skipped`() {
        val pack = IconPackParser.fromZip(
            zip(
                "herald.png" to ByteArray(IconPackParser.MAX_ENTRY_BYTES + 1),
                "guardian.png" to byteArrayOf(1, 2), // fake decoder returns null
                "archon.png" to png(50),
            ),
            fakeDecode,
        )
        assertEquals(setOf(Medal.ARCHON), pack.icons.keys)
    }

    @Test fun `not a zip or no medals is an error`() {
        expectError { IconPackParser.fromZip(ByteArrayInputStream("not a zip".toByteArray()), fakeDecode) }
        expectError { IconPackParser.fromZip(zip("notes.txt" to "x".toByteArray()), fakeDecode) }
    }

    @Test fun `icons are masked to the 489 leds`() {
        val pack = IconPack(mapOf(Medal.LEGEND to IntArray(625) { 4095 }))
        val icon = pack.iconFor(Medal.LEGEND)!!
        assertEquals(0, icon[0])
        assertEquals(489, icon.count { it > 0 })
    }

    @Test fun `storage format round trips`() {
        val original = IconPack(mapOf(Medal.HERALD to IntArray(625) { it % 4096 }, Medal.IMMORTAL to IntArray(625) { 7 }))
        val restored = IconPack.fromJson(original.toJson())
        assertEquals(original.icons.keys, restored.icons.keys)
        for (m in original.icons.keys) assertTrue(original.iconFor(m)!!.contentEquals(restored.iconFor(m)!!))
    }

    @Test fun `partial imported pack overrides bundled medals and preserves the rest`() {
        val bundled = IconPack(Medal.entries.associateWith { IntArray(625) { 300 } })
        val imported = IconPack(mapOf(Medal.HERALD to IntArray(625) { 900 }))
        val combined = bundled.withOverrides(imported)
        assertEquals(Medal.entries.toSet(), combined.icons.keys)
        assertEquals(900, combined.iconFor(Medal.HERALD)!![12 * 25 + 12])
        assertEquals(300, combined.iconFor(Medal.GUARDIAN)!![12 * 25 + 12])
    }

    private fun expectError(block: () -> Unit) {
        try {
            block()
            fail("expected IconPackException")
        } catch (e: IconPackException) {
            // expected
        }
    }
}
