package com.glyphrank.dota.glyph

import com.glyphrank.dota.rank.Medal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MedalArtTest {
    @Test fun `grey 0-255 scales to led range 0-4095`() {
        assertEquals(listOf(0, 2056, 4095), MedalArt.gray255ToFrame(intArrayOf(0, 128, 255)).toList())
    }

    @Test fun `malformed entries are skipped, valid ones kept`() {
        val json = """{"ranks":{"herald":${List(25) { List(25) { 255 } }},"guardian":[[1,2,3]]}}"""
        val art = MedalArt.fromJson(json)
        assertEquals(1, art.size)
        assertEquals(4095, art.frameFor(Medal.HERALD)!![12 * 25 + 12])
        assertEquals(null, art.frameFor(Medal.GUARDIAN))
    }

    @Test fun `art is masked to the 489 leds`() {
        val frame = MedalArt(mapOf(Medal.LEGEND to IntArray(625) { 4095 })).frameFor(Medal.LEGEND)!!
        assertEquals(0, frame[0])
        assertEquals(489, frame.count { it > 0 })
    }

    @Test fun `bundled file has art for all 8 medals`() {
        val art = MedalArt.fromJson(File("src/main/assets/dota_rank_medals.json").readText())
        assertEquals(8, art.size)
        for (medal in Medal.entries) assertTrue(medal.name, art.frameFor(medal)!!.any { it > 0 })
    }
}
