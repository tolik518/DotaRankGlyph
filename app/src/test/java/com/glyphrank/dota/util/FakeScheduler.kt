package com.glyphrank.dota.util

/** [Scheduler] with virtual time: tasks run only when a test advances the clock. */
class FakeScheduler : Scheduler {
    private data class Task(val at: Long, val order: Long, val task: Runnable)

    var now = 0L
        private set
    private var order = 0L
    private val tasks = mutableListOf<Task>()

    override fun post(task: Runnable) = postDelayed(task, 0)

    override fun postDelayed(task: Runnable, delayMs: Long) {
        tasks += Task(now + delayMs, order++, task)
    }

    override fun remove(task: Runnable) {
        tasks.removeAll { it.task === task }
    }

    /** Runs everything due within [ms], including tasks posted meanwhile. */
    fun advanceBy(ms: Long) {
        val end = now + ms
        while (true) {
            val next = tasks.filter { it.at <= end }.minWithOrNull(compareBy({ it.at }, { it.order })) ?: break
            tasks -= next
            now = maxOf(now, next.at)
            next.task.run()
        }
        now = end
    }

    /** Runs the tasks that are due now. */
    fun runDue() = advanceBy(0)

    val isIdle: Boolean get() = tasks.isEmpty()
}
