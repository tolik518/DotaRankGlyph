package com.glyphrank.dota.toy

import android.content.Context
import android.util.Log
import com.glyphrank.dota.data.BundledMedals
import com.glyphrank.dota.data.RankRepository
import com.glyphrank.dota.util.MainThread
import com.glyphrank.dota.widget.RankRefreshJob
import com.nothing.ketchum.GlyphMatrixManager

/**
 * Shows the configured player's Dota 2 rank on the Glyph Matrix.
 *
 *  - Selected:   shows the cached rank instantly, refreshes in the background if stale.
 *  - Long-press: forces a refresh; the last known rank shakes while loading (at least one
 *                full shake, in step with the app via [com.glyphrank.dota.glyph.ReloadShake]), or the ring spinner
 *                runs if there is no rank yet. Reloads from the app shake the Glyph too.
 *  - Rank change: plays [com.glyphrank.dota.glyph.RankAnimation], also for changes that
 *                happened while the toy wasn't on the Glyph.
 *  - Errors:     never shown on the Glyph; the last known medal stays. The app shows them.
 *  - AOD:        re-renders on every system tick (~1/min); fetches only when the cache is stale.
 *  - Fetching:   all requests go through [RankRepository] (rate limits, one request at a time).
 *  - Art:        the bundled Dota 2 medals.
 *
 * The behaviour is in [ToyController]; this service connects it to the matrix.
 */
class DotaRankToyService : GlyphMatrixService("DotaRankToy") {

    private var controller: ToyController? = null

    override fun onMatrixConnected(context: Context, glyphMatrixManager: GlyphMatrixManager) {
        if (DISABLE_SYSTEM_TIMEOUT) {
            // Undocumented SDK 2.0 method; semantics unconfirmed, so off by default.
            runCatching { glyphMatrixManager.setGlyphMatrixTimeout(false) }
                .onFailure { Log.w(TAG, "setGlyphMatrixTimeout failed", it) }
        }
        RankRefreshJob.reschedule(context) // a force-stop cancels the widget's job
        controller = ToyController(RankRepository.get(context), BundledMedals.load(context), MainThread, ::showFrame)
            .also { it.start() }
    }

    override fun onMatrixDisconnected(context: Context) {
        controller?.stop()
        controller = null
    }

    override fun onGlyphButtonLongPress() {
        controller?.onLongPress()
    }

    override fun onAodTick() {
        controller?.onAodTick()
    }

    private companion object {
        const val TAG = "DotaRankToy"

        /** Set to true to try keeping the matrix on instead of the ~3–4 min system timeout. */
        const val DISABLE_SYSTEM_TIMEOUT = false
    }
}
