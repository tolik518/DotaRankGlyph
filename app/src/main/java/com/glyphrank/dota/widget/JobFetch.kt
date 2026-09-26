package com.glyphrank.dota.widget

import com.glyphrank.dota.data.RankRepository

/** One run of [RankRefreshJob]: sends the queued or due request and reports when it is answered. */
class JobFetch(private val repository: RankRepository, private val onDone: () -> Unit) {
    private var waiting: RankRepository.Listener? = null

    /** Returns false if there is nothing to fetch; otherwise calls onDone once the request is answered. */
    fun start(): Boolean {
        if (!repository.runQueuedFetch()) return false
        val listener = object : RankRepository.Listener {
            override fun onStateChanged() {
                if (repository.isLoading) return
                cancel()
                onDone()
            }
        }
        waiting = listener
        repository.addListener(listener)
        return true
    }

    /** Android stopped the job; the request still finishes, but nobody waits for it. */
    fun cancel() {
        waiting?.let(repository::removeListener)
        waiting = null
    }
}
