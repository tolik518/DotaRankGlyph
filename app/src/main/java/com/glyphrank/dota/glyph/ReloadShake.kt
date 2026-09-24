package com.glyphrank.dota.glyph

import android.os.Handler
import android.os.Looper

/**
 * The reload shake, shared by the Glyph toy and the settings screen (same process): a reload
 * started on either side shakes the medal on both, in step. Main thread only.
 *
 * Every reload calls [start] and later [finish] with the token it got. The shake runs while
 * any reload is in flight and always stops at the end of a whole (centred) cycle.
 */
object ReloadShake {
    interface Listener {
        /** Show shake step [step] of the last known medal. */
        fun onShakeStep(step: Int)

        /** The shake is over; show the current state again. */
        fun onShakeEnd()
    }

    /** Safety net in case a reload never reports back (well past the 10 s network timeout). */
    private val MAX_STEPS = (30_000 / RankRenderer.SHAKE_FRAME_MS).toInt()

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    /** Tokens of the reloads in flight. Cleared by the safety stop, so a late [finish] is ignored. */
    private val reloads = HashSet<Int>()
    private var nextToken = 1
    private var step = 0

    var isShaking = false
        private set

    private val tick = object : Runnable {
        override fun run() {
            listeners.toList().forEach { it.onShakeStep(step) }
            step++
            val cycleDone = step % RankRenderer.SHAKE_CYCLE_STEPS == 0
            if (cycleDone && (reloads.isEmpty() || step >= MAX_STEPS)) {
                reloads.clear()
                isShaking = false
                listeners.toList().forEach { it.onShakeEnd() }
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

    /** Starts (or joins) the shake for one reload; pass the token to [finish]. */
    fun start(): Int {
        val token = nextToken++
        reloads += token
        if (!isShaking) {
            isShaking = true
            step = 0
            main.post(tick)
        }
        return token
    }

    fun finish(token: Int) {
        reloads -= token
    }

    /** One full shake cycle: feedback for a reload request that didn't need a request. */
    fun pulse() = finish(start())
}
