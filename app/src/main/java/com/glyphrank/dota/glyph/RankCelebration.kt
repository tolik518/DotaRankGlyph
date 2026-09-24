package com.glyphrank.dota.glyph

import com.glyphrank.dota.util.MainThread

/**
 * Plays the rank-change animation ([RankAnimation]) on the Glyph toy and the settings
 * screen at the same time (same process, like [ReloadShake]). Main thread only.
 *
 * If a reload shake is running, the animation waits for it to finish its cycle, so the
 * medal first settles and then changes. While [isBusy], viewers show nothing else.
 */
object RankCelebration {
    interface Listener {
        fun onCelebrationFrame(frame: IntArray)

        /** The animation is over; show the current state again. */
        fun onCelebrationEnd()
    }

    private val main = MainThread
    private val listeners = LinkedHashSet<Listener>()
    private val glyphListeners = HashSet<Listener>()
    private var frames: List<AnimationFrame> = emptyList()
    private var next = 0
    /** Waiting for the reload shake to end. */
    private var queued = false

    /** Playing, or about to play once the reload shake ends. */
    val isBusy: Boolean get() = frames.isNotEmpty()

    /** Frames are being shown right now (not waiting for the shake). */
    val isPlaying: Boolean get() = isBusy && !queued

    /** True if the Glyph toy is on the matrix to show an animation. */
    val glyphListening: Boolean get() = glyphListeners.isNotEmpty()

    private val step = object : Runnable {
        override fun run() {
            if (next >= frames.size) {
                frames = emptyList()
                listeners.toList().forEach { it.onCelebrationEnd() }
                return
            }
            val frame = frames[next++]
            listeners.toList().forEach { it.onCelebrationFrame(frame.pixels) }
            main.postDelayed(this, frame.durationMs)
        }
    }

    private val shakeListener = object : ReloadShake.Listener {
        override fun onShakeStep(step: Int) = Unit

        override fun onShakeEnd() {
            ReloadShake.removeListener(this)
            if (queued) {
                queued = false
                main.post(step) // after the viewers have handled the end of the shake
            }
        }
    }

    /** [glyph]: the listener draws on the Glyph Matrix (the toy), not the app preview. */
    fun addListener(listener: Listener, glyph: Boolean = false) {
        listeners += listener
        if (glyph) glyphListeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
        glyphListeners -= listener
    }

    /** Plays [animation], replacing one that is still running. Nothing happens for an empty list. */
    fun play(animation: List<AnimationFrame>) {
        if (animation.isEmpty()) return
        main.remove(step)
        frames = animation
        next = 0
        if (ReloadShake.isShaking) {
            queued = true
            ReloadShake.addListener(shakeListener)
        } else {
            queued = false
            main.post(step)
        }
    }
}
