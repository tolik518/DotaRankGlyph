package com.glyphrank.dota.data

import android.content.Context
import android.util.Log
import com.glyphrank.dota.glyph.MedalArt
import com.glyphrank.dota.rank.Medal

/** The Dota 2 medal art bundled in `assets/`, loaded once per process. */
object BundledMedals {
    private const val TAG = "BundledMedals"
    private const val FILE = "dota_rank_medals.json"

    @Volatile private var cached: MedalArt? = null

    /** Null only if the asset is missing or broken; the renderer then uses its built-in emblem. */
    fun load(context: Context): MedalArt? {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: runCatching {
                val json = context.applicationContext.assets.open(FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
                MedalArt.fromJson(json).also {
                    check(it.size == Medal.entries.size) { "Bundled medal art is incomplete (${it.size} of 8)" }
                }
            }.onFailure { Log.e(TAG, "Bundled medal art unreadable", it) }.getOrNull()?.also { cached = it }
        }
    }
}
