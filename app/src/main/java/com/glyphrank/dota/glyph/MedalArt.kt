package com.glyphrank.dota.glyph

import com.glyphrank.dota.rank.Medal
import org.json.JSONObject

/**
 * Grayscale Dota 2 medal art for the Glyph Matrix: one 25x25 frame (values 0..4095) per
 * medal, masked to the 489 LEDs. Medals without art fall back to the built-in emblem.
 */
class MedalArt(frames: Map<Medal, IntArray>) {
    private val frames: Map<Medal, IntArray> = frames.mapValues { (_, frame) -> maskToLeds(frame) }

    fun frameFor(medal: Medal): IntArray? = frames[medal]

    val size: Int get() = frames.size

    companion object {
        /**
         * Parses `{"ranks": {"herald": [[25 rows × 25 values 0..255]], ...}}` (the format of the
         * bundled `dota_rank_medals.json`). Malformed entries are skipped.
         */
        fun fromJson(json: String): MedalArt {
            val ranks = JSONObject(json).optJSONObject("ranks") ?: return MedalArt(emptyMap())
            val frames = Medal.entries.mapNotNull { medal ->
                val rows = ranks.optJSONArray(medal.jsonKey) ?: return@mapNotNull null
                if (rows.length() != MatrixLayout.SIZE) return@mapNotNull null
                val gray = IntArray(FRAME_SIZE)
                for (y in 0 until MatrixLayout.SIZE) {
                    val row = rows.optJSONArray(y) ?: return@mapNotNull null
                    if (row.length() != MatrixLayout.SIZE) return@mapNotNull null
                    for (x in 0 until MatrixLayout.SIZE) gray[y * MatrixLayout.SIZE + x] = row.optInt(x)
                }
                medal to gray255ToFrame(gray)
            }.toMap()
            return MedalArt(frames)
        }

        /** 0..255 grey -> 0..4095 LED brightness. */
        fun gray255ToFrame(gray: IntArray): IntArray =
            IntArray(gray.size) { (gray[it].coerceIn(0, 255) * MatrixLayout.MAX_BRIGHTNESS + 127) / 255 }

        private fun maskToLeds(frame: IntArray): IntArray {
            require(frame.size == FRAME_SIZE) { "Medal art must be 25x25" }
            return IntArray(FRAME_SIZE) { i ->
                val x = i % MatrixLayout.SIZE
                val y = i / MatrixLayout.SIZE
                if (MatrixLayout.isLed(x, y)) frame[i].coerceIn(0, MatrixLayout.MAX_BRIGHTNESS) else 0
            }
        }
    }
}

internal const val FRAME_SIZE = MatrixLayout.SIZE * MatrixLayout.SIZE

/** Lower-case key used in the medal JSON: "herald", "guardian", ... */
val Medal.jsonKey: String get() = name.lowercase()
