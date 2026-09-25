package com.glyphrank.dota.widget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.glyphrank.dota.data.RankRepository
import com.glyphrank.dota.data.RankStore
import com.glyphrank.dota.data.RefreshInterval

/**
 * Background fetches, as jobs because Android blocks this app's network in the background
 * except while one of its jobs runs:
 *
 *  - Periodic ([schedule]): refreshes the rank while a home-screen widget exists, at the Auto
 *    refresh interval (at least every 15 min, Android's minimum).
 *  - Once, expedited ([scheduleFetchNow]): sends a request that [RankRepository] queued
 *    because the app had no network (e.g. a Glyph long-press with the app closed).
 *
 * Both go through [RankRepository], so the rate-limit rules apply and nothing fetches twice.
 */
class RankRefreshJob : JobService() {
    private var waiting: RankRepository.Listener? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val repo = RankRepository.get(this)
        // Both jobs may use the network while they run, even with the app in the background.
        val running = repo.runQueuedFetch()
        Log.d(TAG, "Job ${params.jobId}: ${if (running) "fetching" else "nothing to fetch"}")
        if (!running) return false
        val listener = object : RankRepository.Listener {
            override fun onStateChanged() {
                if (repo.isLoading) return
                repo.removeListener(this)
                waiting = null
                jobFinished(params, false)
            }
        }
        waiting = listener
        repo.addListener(listener)
        return true // finished by the listener
    }

    override fun onStopJob(params: JobParameters): Boolean {
        waiting?.let { RankRepository.get(this).removeListener(it) }
        waiting = null
        return false // the next periodic run will try again
    }

    companion object {
        private const val TAG = "RankRefreshJob"
        private const val JOB_ID = 4045
        private const val FETCH_NOW_JOB_ID = 4046

        /** Runs a queued request as soon as the network may be used (right away if online). */
        fun scheduleFetchNow(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val component = ComponentName(context, RankRefreshJob::class.java)
            fun job(expedited: Boolean) = JobInfo.Builder(FETCH_NOW_JOB_ID, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExpedited(expedited)
                .build()
            // Expedited jobs start within seconds but have a quota; fall back to a normal job.
            val result = runCatching { scheduler.schedule(job(expedited = true)) }.getOrDefault(JobScheduler.RESULT_FAILURE)
            if (result == JobScheduler.RESULT_SUCCESS) return
            Log.w(TAG, "Expedited job refused, scheduling a normal one")
            runCatching { scheduler.schedule(job(expedited = false)) }
                .onFailure { Log.w(TAG, "Could not schedule the fetch job", it) }
        }

        /** Schedules (or re-times) the periodic refresh; keeps a pending job with the same interval. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val intervalMs = maxOf(
                JobInfo.getMinPeriodMillis(),
                RefreshInterval.toMillis(RankStore(context).refreshIntervalMinutes),
            )
            if (scheduler.getPendingJob(JOB_ID)?.intervalMillis == intervalMs) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, RankRefreshJob::class.java))
                .setPeriodic(intervalMs)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            // Never let scheduling break the widget; it still updates whenever the app or toy refreshes.
            runCatching { scheduler.schedule(job) }
                .onSuccess { Log.d(TAG, "Scheduled every ${intervalMs / 60_000} min") }
                .onFailure { Log.w(TAG, "Could not schedule the widget refresh", it) }
        }

        /** Re-times the job after the Auto refresh setting changed, if there are widgets. */
        fun reschedule(context: Context) {
            if (RankWidget.hasWidgets(context)) schedule(context)
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }
    }
}
