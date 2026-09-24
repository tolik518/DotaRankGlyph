package com.glyphrank.dota.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Log
import com.glyphrank.dota.glyph.IconPack
import com.glyphrank.dota.glyph.IconPackException
import com.glyphrank.dota.glyph.IconPackParser
import com.glyphrank.dota.glyph.MatrixLayout
import java.io.File

/** Bundled Dota medal art plus an optional user icon pack stored in private app files. */
class IconPackStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)

    /** Imports a zip picked by the user (Storage Access Framework URI). Blocking. */
    fun import(context: Context, uri: Uri): IconPack {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IconPackException("Could not open the selected file")
        val pack = stream.use { IconPackParser.fromZip(it, ::decodeToGray25) }
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(pack.toJson())
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IconPackException("Could not save the icon pack")
        }
        synchronized(lock) { cache = null }
        return pack
    }

    /** The saved pack, or null. Cached until the file changes. */
    fun load(): IconPack? = synchronized(lock) {
        if (!file.exists()) return null
        val stamp = file.lastModified() to file.length()
        cache?.let { (cachedStamp, pack) -> if (cachedStamp == stamp) return pack }
        val pack = runCatching { IconPack.fromJson(file.readText()) }
            .onFailure { Log.w(TAG, "Stored icon pack unreadable", it) }
            .getOrNull()
        cache = pack?.let { stamp to it }
        pack
    }

    /** Bundled medal images are the default; an imported pack overrides matching ranks. */
    fun displayPack(useImported: Boolean): IconPack? {
        val bundled = loadBundledMedals()
        val imported = if (useImported) load() else null
        return when {
            bundled != null -> bundled.withOverrides(imported)
            else -> imported
        }
    }

    private fun loadBundledMedals(): IconPack? = synchronized(lock) {
        bundledCache?.let { return it }
        val pack = runCatching {
            val json = appContext.assets.open(BUNDLED_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val icons = IconPackParser.fromBitmapsJson(json)
            if (icons.size != 8) throw IconPackException("Bundled Dota medal pack is incomplete")
            IconPack(icons)
        }.onFailure { Log.e(TAG, "Bundled Dota medal pack unreadable", it) }.getOrNull()
        pack?.also { bundledCache = it }
    }

    fun delete() {
        synchronized(lock) { cache = null }
        file.delete()
    }

    companion object {
        private const val TAG = "IconPackStore"
        private const val FILE_NAME = "icon_pack.json"
        private const val BUNDLED_FILE = "dota_rank_medals.json"
        private val lock = Any()
        @Volatile private var cache: Pair<Pair<Long, Long>, IconPack>? = null
        @Volatile private var bundledCache: IconPack? = null

        /** Any square image -> 25x25 grey values 0..255 (alpha counts as darkness). */
        fun decodeToGray25(bytes: ByteArray): IntArray? {
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            if (decoded.width != decoded.height) {
                decoded.recycle()
                return null
            }
            val size = MatrixLayout.SIZE
            val bitmap: Bitmap = if (decoded.width == size) decoded
            else Bitmap.createScaledBitmap(decoded, size, size, true).also { decoded.recycle() }
            val argb = IntArray(size * size)
            bitmap.getPixels(argb, 0, size, 0, 0, size, size)
            bitmap.recycle()
            return IntArray(argb.size) { i ->
                val c = argb[i]
                val lum = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
                (lum * Color.alpha(c) / 255.0).toInt().coerceIn(0, 255)
            }
        }
    }
}
