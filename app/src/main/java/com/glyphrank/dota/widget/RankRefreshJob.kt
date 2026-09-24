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
import com.glyphrank.dota.data.RefreshPolicy.Decision

/**
 * Refreshes the rank in the background while a home-screen widget exists, at the Auto refresh
 * interval (at least every 15 min, Android's minimum). Goes through [RankRepository], so the
 * rate-limit rules apply and the toy and the widget never both fetch.
 */
class RankRefreshJob : JobService() {
    private var waiting: RankRepository.Listener? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val repo = RankRepository.get(this)
        val decision = repo.refresh(manual = false)
        Log.d(TAG, "Widget refresh: $decision")
        if (decision != Decision.Fetch && decision != Decision.Joined) return false
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
