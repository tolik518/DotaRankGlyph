package com.glyphrank.dota.util

import android.os.Handler
import android.os.Looper

/** Posts work to a thread; [MainThread] in the app, a fake with virtual time in unit tests. */
interface Scheduler {
    fun post(task: Runnable)
    fun postDelayed(task: Runnable, delayMs: Long)
    fun remove(task: Runnable)
}

/** The main thread. JVM unit tests set [delegate] to a fake, since there is no Looper there. */
object MainThread : Scheduler {
    @Volatile var delegate: Scheduler? = null

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun post(task: Runnable) {
        delegate?.post(task) ?: handler.post(task)
    }

    override fun postDelayed(task: Runnable, delayMs: Long) {
        delegate?.postDelayed(task, delayMs) ?: handler.postDelayed(task, delayMs)
    }

    override fun remove(task: Runnable) {
        delegate?.remove(task) ?: handler.removeCallbacks(task)
    }
}
