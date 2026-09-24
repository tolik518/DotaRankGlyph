package com.glyphrank.dota.glyph

import com.glyphrank.dota.rank.Medal
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * A user-imported set of medal icons: one 25x25 frame (values 0..4095) per medal.
 * Medals missing from the pack fall back to the built-in emblem.
 */
class IconPack(icons: Map<Medal, IntArray>) {
    val icons: Map<Medal, IntArray> = icons.mapValues { (_, frame) -> maskToLeds(frame) }

    fun iconFor(medal: Medal): IntArray? = icons[medal]

    val size: Int get() = icons.size

    /** Returns a pack where [overrides] replace matching medals and missing entries fall back to this pack. */
    fun withOverrides(overrides: IconPack?): IconPack =
        if (overrides == null) this else IconPack(icons + overrides.icons)

    /** Storage format written to the app's private files dir. */
    fun toJson(): String = JSONObject().apply {
        put("format", STORAGE_FORMAT)
        put("icons", JSONObject().apply {
            for ((medal, frame) in icons) put(medal.fileKey, JSONArray(frame.toList()))
        })
    }.toString()

    companion object {
        private const val STORAGE_FORMAT = "dota-rank-glyph-icons/1"

        fun fromJson(json: String): IconPack {
            val root = try { JSONObject(json) } catch (e: JSONException) {
                throw IconPackException("Stored icon pack is corrupt", e)
            }
            if (root.optString("format") != STORAGE_FORMAT) throw IconPackException("Unknown icon pack format")
            val obj = root.getJSONObject("icons")
            val icons = Medal.entries.mapNotNull { medal ->
                val arr = obj.optJSONArray(medal.fileKey) ?: return@mapNotNull null
                if (arr.length() != FRAME_SIZE) return@mapNotNull null
                medal to IntArray(FRAME_SIZE) { arr.getInt(it) }
            }.toMap()
            return IconPack(icons)
        }

        private fun maskToLeds(frame: IntArray): IntArray {
            require(frame.size == FRAME_SIZE) { "Icon must be 25x25" }
            return IntArray(FRAME_SIZE) { i ->
                val x = i % MatrixLayout.SIZE
                val y = i / MatrixLayout.SIZE
                if (MatrixLayout.isLed(x, y)) frame[i].coerceIn(0, MatrixLayout.MAX_BRIGHTNESS) else 0
            }
        }
    }
}

class IconPackException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal const val FRAME_SIZE = MatrixLayout.SIZE * MatrixLayout.SIZE

/** Lowercase key used for file names and JSON keys: "herald", "guardian", ... */
val Medal.fileKey: String get() = name.lowercase()

/**
 * Reads an icon pack from a zip. Recognised contents (other files are ignored):
 *  - `bitmaps.json` with `{"ranks": {"herald": [[25 x 25 values 0..255]], ...}}`
 *  - image files whose name contains a medal name, e.g. `herald.png`, `medal_divine.png`
 *
 * JSON entries win; images fill in medals the JSON doesn't have.
 * Image decoding is platform-specific, so it's passed in: bytes -> 625 grey values (0..255) or null.
 */
object IconPackParser {
    const val MAX_ENTRY_BYTES = 2 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 16 * 1024 * 1024
    private val IMAGE_EXTENSIONS = setOf("png", "webp", "jpg", "jpeg", "bmp")

    fun fromZip(input: InputStream, decodeImage: (ByteArray) -> IntArray?): IconPack {
        val fromJson = mutableMapOf<Medal, IntArray>()
        val fromImages = mutableMapOf<Medal, IntArray>()
        var total = 0L
        var sawZipEntry = false
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    sawZipEntry = true
                    if (entry.isDirectory) continue
                    val name = entry.name.substringAfterLast('/')
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val isJson = ext == "json"
                    val medal = medalForFileName(name)
                    if (!isJson && (medal == null || ext !in IMAGE_EXTENSIONS)) continue
                    val bytes = readLimited(zip) ?: continue // oversized entry: skip it
                    total += bytes.size
                    if (total > MAX_TOTAL_BYTES) throw IconPackException("Icon pack is too large")
                    if (isJson) {
                        runCatching { fromBitmapsJson(String(bytes, Charsets.UTF_8)) }
                            .onSuccess { fromJson.putAll(it) }
                    } else if (medal != null) {
                        decodeImage(bytes)?.takeIf { it.size == FRAME_SIZE }?.let {
                            fromImages[medal] = gray255ToFrame(it)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            throw IconPackException("Could not read the zip file", e)
        }
        if (!sawZipEntry) throw IconPackException("That file isn't a zip, or it's empty")
        val icons = fromImages + fromJson
        if (icons.isEmpty()) {
            throw IconPackException("No medal icons found (expected herald.png … immortal.png or bitmaps.json)")
        }
        return IconPack(icons)
    }

    /** Parses `{"ranks": {"herald": [[...25 rows of 25 values 0..255...]], ...}}`. */
    fun fromBitmapsJson(json: String): Map<Medal, IntArray> {
        val ranks = JSONObject(json).optJSONObject("ranks") ?: return emptyMap()
        return Medal.entries.mapNotNull { medal ->
            val rows = ranks.optJSONArray(medal.fileKey) ?: return@mapNotNull null
            if (rows.length() != MatrixLayout.SIZE) return@mapNotNull null
            val gray = IntArray(FRAME_SIZE)
            for (y in 0 until MatrixLayout.SIZE) {
                val row = rows.optJSONArray(y) ?: return@mapNotNull null
                if (row.length() != MatrixLayout.SIZE) return@mapNotNull null
                for (x in 0 until MatrixLayout.SIZE) gray[y * MatrixLayout.SIZE + x] = row.optInt(x)
            }
            medal to gray255ToFrame(gray)
        }.toMap()
    }

    /** "herald.png", "Medal_Herald.PNG", "pack/herald.png" -> HERALD; anything else -> null. */
    fun medalForFileName(fileName: String): Medal? {
        val base = fileName.substringAfterLast('/').substringBeforeLast('.').lowercase()
        return Medal.entries.firstOrNull { base.contains(it.fileKey) }
    }

    /** 0..255 grey -> 0..4095 LED brightness. */
    fun gray255ToFrame(gray: IntArray): IntArray =
        IntArray(gray.size) { (gray[it].coerceIn(0, 255) * MatrixLayout.MAX_BRIGHTNESS + 127) / 255 }

    private fun readLimited(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var read = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            read += n
            if (read > MAX_ENTRY_BYTES) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
