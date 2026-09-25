package com.glyphrank.dota.toy

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.glyphrank.dota.data.BundledMedals
import com.glyphrank.dota.data.RankRepository
import com.glyphrank.dota.data.RankStore
import com.glyphrank.dota.data.RefreshPolicy.Decision
import com.glyphrank.dota.glyph.MedalArt
import com.glyphrank.dota.glyph.RankCelebration
import com.glyphrank.dota.glyph.RankRenderer
import com.glyphrank.dota.glyph.ReloadShake
import com.glyphrank.dota.widget.RankRefreshJob
import com.nothing.ketchum.GlyphMatrixManager

/**
 * Shows the configured player's Dota 2 rank on the Glyph Matrix.
 *
 *  - Selected:   shows the cached rank instantly, refreshes in the background if stale.
 *  - Long-press: forces a refresh; the last known rank shakes while loading (at least one
 *                full shake, in step with the app via [ReloadShake]), or the ring spinner
 *                runs if there is no rank yet. Reloads from the app shake the Glyph too.
 *  - Rank change: plays [com.glyphrank.dota.glyph.RankAnimation], also for changes that
 *                happened while the toy wasn't on the Glyph.
 *  - Errors:     never shown on the Glyph; the last known medal stays. The app shows them.
 *  - AOD:        re-renders on every system tick (~1/min); fetches only when the cache is stale.
 *  - Fetching:   all requests go through [RankRepository] (rate limits, one request at a time).
 *  - Art:        the bundled Dota 2 medals.
 */
class DotaRankToyService : GlyphMatrixService("DotaRankToy") {

    private val main = Handler(Looper.getMainLooper())
    private val renderer = RankRenderer()
    private var repository: RankRepository? = null
    private var medals: MedalArt? = null

    private var spinnerStep = 0
    /** The medal being shaken; null when idle. */
    private var shakeBase: IntArray? = null

    /** Ring spinner, only while loading the first rank for an account (nothing to shake yet). */
    private val spinner = object : Runnable {
        override fun run() {
            showFrame(renderer.loading(spinnerStep++))
            main.postDelayed(this, SPINNER_FRAME_MS)
        }
    }

    /** Follows the shared reload shake, whether the reload started here or in the app. */
    private val shakeListener = object : ReloadShake.Listener {
        override fun onShakeStep(step: Int) {
            if (RankCelebration.isPlaying) return
            val base = shakeBase ?: cachedMedal() ?: return
            shakeBase = base
            showFrame(renderer.shake(base, step))
        }

        override fun onShakeEnd() {
            shakeBase = null
            showCurrent()
        }
    }

    private val celebrationListener = object : RankCelebration.Listener {
        override fun onCelebrationFrame(frame: IntArray) = showFrame(frame)

        override fun onCelebrationEnd() = showCurrent()
    }

    private val repositoryListener = object : RankRepository.Listener {
        override fun onStateChanged() {
            val repo = repository ?: return
            val needsSpinner = repo.isLoading && repo.store.cachedForCurrentAccount() == null
            if (needsSpinner && !main.hasCallbacks(spinner)) {
                spinnerStep = 0
                main.post(spinner)
            } else if (!needsSpinner) {
                main.removeCallbacks(spinner)
            }
            showCurrent()
        }
    }

    // Held in a field: SharedPreferences only keeps a weak reference to listeners.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (RankStore.isBookkeeping(key)) return@OnSharedPreferenceChangeListener
        if (key == RankStore.KEY_REFRESH_INTERVAL) repository?.refresh(manual = false)
        showCurrent()
    }

    override fun onMatrixConnected(context: Context, glyphMatrixManager: GlyphMatrixManager) {
        val repo = RankRepository.get(context)
        repository = repo
        if (!repo.store.toyUsed) repo.store.toyUsed = true // no need to suggest adding the toy
        medals = BundledMedals.load(context)
        if (DISABLE_SYSTEM_TIMEOUT) {
            // Undocumented SDK 2.0 method; semantics unconfirmed, so off by default.
            runCatching { glyphMatrixManager.setGlyphMatrixTimeout(false) }
                .onFailure { Log.w(TAG, "setGlyphMatrixTimeout failed", it) }
        }
        repo.store.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        repo.addListener(repositoryListener)
        ReloadShake.addListener(shakeListener)
        RankCelebration.addListener(celebrationListener, glyph = true)
        repo.playPendingCelebration() // before showCurrent, so the new rank isn't shown first
        RankRefreshJob.reschedule(context) // a force-stop cancels the widget's job
        showCurrent()
        repo.refresh(manual = false)
    }

    override fun onMatrixDisconnected(context: Context) {
        repository?.let {
            it.store.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
            it.removeListener(repositoryListener)
        }
        repository = null
        ReloadShake.removeListener(shakeListener)
        RankCelebration.removeListener(celebrationListener)
        shakeBase = null
        main.removeCallbacksAndMessages(null)
    }

    override fun onGlyphButtonLongPress() {
        val repo = repository ?: return
        when (repo.refresh(manual = true)) {
            Decision.Fetch, Decision.Joined, Decision.NoAccount -> Unit
            // Nothing to load right now (just checked, or rate limited): one shake as feedback.
            else -> if (repo.store.cachedForCurrentAccount() != null) ReloadShake.pulse()
        }
    }

    override fun onAodTick() {
        showCurrent()
        repository?.refresh(manual = false)
    }

    private fun showCurrent() {
        // An animation owns the matrix.
        if (ReloadShake.isShaking || RankCelebration.isBusy || main.hasCallbacks(spinner)) return
        showFrame(currentFrame())
    }

    /** The last known medal. Errors are only shown in the app, never on the Glyph. */
    private fun currentFrame(): IntArray {
        val store = repository?.store ?: return renderer.loading(0)
        if (store.accountId == null) return renderer.message("ID")
        return cachedMedal() ?: renderer.loading(0) // no rank for this account yet
    }

    private fun cachedMedal(): IntArray? {
        val store = repository?.store ?: return null
        return store.cachedForCurrentAccount()?.let {
            renderer.render(it.player.state, medals, store.showImmortalRank)
        }
    }

    private companion object {
        const val TAG = "DotaRankToy"
        const val SPINNER_FRAME_MS = 70L

        /** Set to true to try keeping the matrix on instead of the ~3–4 min system timeout. */
        const val DISABLE_SYSTEM_TIMEOUT = false
    }
}
