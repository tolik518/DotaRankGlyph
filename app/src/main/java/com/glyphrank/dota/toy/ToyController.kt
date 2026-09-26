package com.glyphrank.dota.toy

import android.content.SharedPreferences
import com.glyphrank.dota.data.RankRepository
import com.glyphrank.dota.data.RankStore
import com.glyphrank.dota.data.RefreshPolicy.Decision
import com.glyphrank.dota.glyph.MedalArt
import com.glyphrank.dota.glyph.RankCelebration
import com.glyphrank.dota.glyph.RankRenderer
import com.glyphrank.dota.glyph.ReloadShake
import com.glyphrank.dota.util.Scheduler

/**
 * What the Glyph toy shows and when it refreshes, see [DotaRankToyService]; the service only
 * connects it to the matrix. Lives from [start] (toy on the matrix) to [stop]. Main thread only.
 */
class ToyController(
    private val repository: RankRepository,
    private val medals: MedalArt?,
    private val main: Scheduler,
    /** Pushes a frame to the Glyph Matrix. */
    private val show: (IntArray) -> Unit,
) {
    private val store: RankStore get() = repository.store
    private val renderer = RankRenderer()

    private var spinnerStep = 0
    private var spinning = false
    /** The medal being shaken; null when idle. */
    private var shakeBase: IntArray? = null

    /** Ring spinner, only while loading the first rank for an account (nothing to shake yet). */
    private val spinner = object : Runnable {
        override fun run() {
            show(renderer.loading(spinnerStep++))
            main.postDelayed(this, SPINNER_FRAME_MS)
        }
    }

    /** Follows the shared reload shake, whether the reload started here or in the app. */
    private val shakeListener = object : ReloadShake.Listener {
        override fun onShakeStep(step: Int) {
            if (RankCelebration.isPlaying) return
            val base = shakeBase ?: cachedMedal() ?: return
            shakeBase = base
            show(renderer.shake(base, step))
        }

        override fun onShakeEnd() {
            shakeBase = null
            showCurrent()
        }
    }

    private val celebrationListener = object : RankCelebration.Listener {
        override fun onCelebrationFrame(frame: IntArray) = show(frame)

        override fun onCelebrationEnd() = showCurrent()
    }

    private val repositoryListener = object : RankRepository.Listener {
        override fun onStateChanged() {
            val needsSpinner = repository.isLoading && store.cachedForCurrentAccount() == null
            if (needsSpinner && !spinning) {
                spinning = true
                spinnerStep = 0
                main.post(spinner)
            } else if (!needsSpinner) {
                stopSpinner()
            }
            showCurrent()
        }
    }

    // Held in a field: SharedPreferences only keeps a weak reference to listeners.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (RankStore.isBookkeeping(key)) return@OnSharedPreferenceChangeListener
        if (key == RankStore.KEY_REFRESH_INTERVAL) repository.refresh(manual = false)
        showCurrent()
    }

    /** The toy is on the matrix: shows the rank, plays a missed rank change, refreshes if stale. */
    fun start() {
        if (!store.toyUsed) store.toyUsed = true // no need to suggest adding the toy
        store.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        repository.addListener(repositoryListener)
        ReloadShake.addListener(shakeListener)
        RankCelebration.addListener(celebrationListener, glyph = true)
        repository.playPendingCelebration() // before showCurrent, so the new rank isn't shown first
        showCurrent()
        repository.refresh(manual = false)
    }

    fun stop() {
        store.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        repository.removeListener(repositoryListener)
        ReloadShake.removeListener(shakeListener)
        RankCelebration.removeListener(celebrationListener)
        shakeBase = null
        stopSpinner()
    }

    /** Long-press of the Glyph Button: check now. */
    fun onLongPress() {
        when (repository.refresh(manual = true)) {
            Decision.Fetch, Decision.Joined, Decision.NoAccount -> Unit
            // Nothing to load right now (just checked, or rate limited): one shake as feedback.
            else -> if (store.cachedForCurrentAccount() != null) ReloadShake.pulse()
        }
    }

    /** About once a minute as the always-on toy. */
    fun onAodTick() {
        showCurrent()
        repository.refresh(manual = false)
    }

    private fun stopSpinner() {
        spinning = false
        main.remove(spinner)
    }

    private fun showCurrent() {
        // An animation owns the matrix.
        if (ReloadShake.isShaking || RankCelebration.isBusy || spinning) return
        show(currentFrame())
    }

    /** The last known medal. Errors are only shown in the app, never on the Glyph. */
    private fun currentFrame(): IntArray {
        if (store.accountId == null) return renderer.message("ID")
        return cachedMedal() ?: renderer.loading(0) // no rank for this account yet
    }

    private fun cachedMedal(): IntArray? =
        store.cachedForCurrentAccount()?.let { renderer.render(it.player.state, medals, store.showImmortalRank) }

    companion object {
        const val SPINNER_FRAME_MS = 70L
    }
}
