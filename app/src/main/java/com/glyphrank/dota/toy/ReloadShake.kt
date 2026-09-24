package com.glyphrank.dota.toy

import android.os.Handler
import android.os.Looper
import com.glyphrank.dota.glyph.RankRenderer

/**
 * The reload shake, shared by the Glyph toy and the settings screen (same process): a reload
 * started on either side shakes the medal on both, in step. Main thread only.
 *
 * Every reload calls [start] and later exactly one [finish]. The shake runs while any reload
 * is in flight and always stops at the end of a whole (centred) cycle.
 */
object ReloadShake {
    interface Listener {
        /** Show shake step [step] of the last known medal. */
        fun onShakeStep(step: Int)

        /** The shake is over; [error] is set if a reload behind it failed. */
        fun onShakeEnd(error: Throwable?)
    }

    /** Safety net in case a reload never reports back (well past the 10 s network timeout). */
    private val MAX_STEPS = (30_000 / RankRenderer.SHAKE_FRAME_MS).toInt()

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private var reloads = 0
    private var step = 0
    private var error: Throwable? = null

    var isShaking = false
        private set

    private val tick = object : Runnable {
        override fun run() {
            listeners.toList().forEach { it.onShakeStep(step) }
            step++
            val cycleDone = step % RankRenderer.SHAKE_CYCLE_STEPS == 0
            if (cycleDone && (reloads == 0 || step >= MAX_STEPS)) {
                reloads = 0
                isShaking = false
                val e = error
                error = null
                listeners.toList().forEach { it.onShakeEnd(e) }
            } else {
                main.postDelayed(this, RankRenderer.SHAKE_FRAME_MS)
            }
        }
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun start() {
        reloads++
        if (isShaking) return
        isShaking = true
        step = 0
        error = null
        main.post(tick)
    }

    fun finish(error: Throwable? = null) {
        if (reloads > 0) reloads--
        if (error != null) this.error = error
    }
}
