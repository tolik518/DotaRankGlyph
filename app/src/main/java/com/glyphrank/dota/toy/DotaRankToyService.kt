package com.glyphrank.dota.toy

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.glyphrank.dota.data.IconPackStore
import com.glyphrank.dota.data.OpenDotaClient
import com.glyphrank.dota.data.RankStore
import com.glyphrank.dota.data.RefreshInterval
import com.glyphrank.dota.glyph.RankRenderer
import com.nothing.ketchum.GlyphMatrixManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Shows the configured player's Dota 2 rank on the Glyph Matrix.
 *
 *  - Selected:   shows the cached rank instantly, refreshes in the background if stale.
 *  - Long-press: forces a refresh; the last known rank shakes while loading (at least one
 *                full shake, in step with the app via [ReloadShake]), or the ring spinner
 *                runs if there is no rank yet. Reloads from the app shake the Glyph too.
 *  - Errors:     never shown on the Glyph; the last known medal stays. The app shows them.
 *  - AOD:        re-renders on every system tick (~1/min); fetches only when the cache is stale.
 *  - Stale:      older than the user's refresh interval (5 min .. once a day).
 *  - Art:        bundled Dota 2 medals, optionally overridden by the user's imported pack.
 */
class DotaRankToyService : GlyphMatrixService("DotaRankToy") {

    private val main = Handler(Looper.getMainLooper())
    private val renderer = RankRenderer()
    private val client = OpenDotaClient()
    private var store: RankStore? = null
    private var iconPacks: IconPackStore? = null
    private var io: ExecutorService? = null

    private var loading = false
    private var lastRequestAt = 0L
    private var spinnerStep = 0
    /** True while our long-press reload is holding the shared [ReloadShake]. */
    private var ownsShake = false
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
            val base = shakeBase ?: cachedMedal() ?: return
            shakeBase = base
            showFrame(renderer.shake(base, step))
        }

        override fun onShakeEnd(error: Throwable?) {
            shakeBase = null
            showCurrent() // errors are only shown in the app
        }
    }

    // Held in a field: SharedPreferences only keeps a weak reference to listeners.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == RankStore.KEY_ACCOUNT_ID || key == RankStore.KEY_REFRESH_INTERVAL) refresh(force = false)
        showCurrent()
    }

    override fun onMatrixConnected(context: Context, glyphMatrixManager: GlyphMatrixManager) {
        val s = RankStore(context)
        store = s
        iconPacks = IconPackStore(context)
        io = Executors.newSingleThreadExecutor()
        if (DISABLE_SYSTEM_TIMEOUT) {
            // Undocumented SDK 2.0 method; semantics unconfirmed, so off by default.
            runCatching { glyphMatrixManager.setGlyphMatrixTimeout(false) }
                .onFailure { Log.w(TAG, "setGlyphMatrixTimeout failed", it) }
        }
        s.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        ReloadShake.addListener(shakeListener)
        showCurrent()
        refresh(force = false)
    }

    override fun onMatrixDisconnected(context: Context) {
        store?.prefs?.unregisterOnSharedPreferenceChangeListener(prefsListener)
        ReloadShake.removeListener(shakeListener)
        releaseShake(null)
        shakeBase = null
        main.removeCallbacksAndMessages(null)
        io?.shutdownNow()
        io = null
        loading = false
    }

    override fun onGlyphButtonLongPress() = refresh(force = true)

    override fun onAodTick() {
        showCurrent()
        refresh(force = false)
    }

    private fun showCurrent() {
        if (ReloadShake.isShaking || main.hasCallbacks(spinner)) return // an animation owns the matrix
        showFrame(currentFrame())
    }

    /** The last known medal. Errors are only shown in the app, never on the Glyph. */
    private fun currentFrame(): IntArray {
        val s = store ?: return renderer.loading(0)
        if (s.accountId == null) return renderer.message("ID")
        return cachedMedal() ?: renderer.loading(0) // no rank for this account yet
    }

    private fun cachedMedal(): IntArray? {
        val s = store ?: return null
        return s.cachedForCurrentAccount()?.let { renderer.render(it.player.state, activeIconPack(s)) }
    }

    /** Bundled Dota medals are the default; imported icons override them when selected. */
    private fun activeIconPack(s: RankStore) = iconPacks?.displayPack(s.useIconPack)

    private fun refresh(force: Boolean) {
        val s = store ?: return
        val executor = io ?: return
        val accountId = s.accountId ?: return showCurrent()
        val cached = s.cachedForCurrentAccount()
        val stale = cached == null ||
            RefreshInterval.isStale(cached.fetchedAtMs, System.currentTimeMillis(), s.refreshIntervalMinutes)
        val now = SystemClock.elapsedRealtime()
        if (loading || (!force && !stale)) return
        if (lastRequestAt != 0L && now - lastRequestAt < MIN_REQUEST_GAP_MS) return

        lastRequestAt = now
        loading = true
        if (cached == null) {
            main.removeCallbacks(spinner)
            spinnerStep = 0
            main.post(spinner)
        } else if (force) {
            ownsShake = true
            ReloadShake.start() // also shakes the preview if the app is open
        }

        try {
            executor.execute {
                val result = runCatching { client.fetchPlayer(accountId) }
                main.post { onFetched(accountId, result) }
            }
        } catch (e: RejectedExecutionException) {
            loading = false
            main.removeCallbacks(spinner)
            releaseShake(e)
        }
    }

    private fun onFetched(accountId: Long, result: Result<com.glyphrank.dota.data.PlayerRank>) {
        loading = false
        releaseShake(result.exceptionOrNull()) // the shake still finishes its cycle
        val s = store ?: return
        result
            .onSuccess { player -> if (s.accountId == accountId) s.save(player) } // ignore if the ID changed mid-request
            .onFailure { Log.w(TAG, "Rank refresh failed: ${it.message}") } // the last known medal stays up
        main.removeCallbacks(spinner)
        showCurrent()
    }

    private fun releaseShake(error: Throwable?) {
        if (!ownsShake) return
        ownsShake = false
        ReloadShake.finish(error)
    }

    private companion object {
        const val TAG = "DotaRankToy"
        const val MIN_REQUEST_GAP_MS = 5_000L
        const val SPINNER_FRAME_MS = 70L

        /** Set to true to try keeping the matrix on instead of the ~3–4 min system timeout. */
        const val DISABLE_SYSTEM_TIMEOUT = false
    }
}
