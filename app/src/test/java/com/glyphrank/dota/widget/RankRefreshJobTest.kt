package com.glyphrank.dota.widget

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.appwidget.AppWidgetManager
import com.glyphrank.dota.R
import com.glyphrank.dota.data.RankStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Scheduling of the background fetch jobs. */
@RunWith(RobolectricTestRunner::class)
class RankRefreshJobTest {
    private val app = RuntimeEnvironment.getApplication()
    private val jobs = app.getSystemService(JobScheduler::class.java)
    private val store = RankStore(app)

    private fun periodic(): JobInfo? = jobs.getPendingJob(4045)

    @Test fun `the periodic refresh follows the auto refresh interval`() {
        store.refreshIntervalMinutes = 60
        RankRefreshJob.schedule(app)
        val job = periodic()!!
        assertEquals(60 * 60_000L, job.intervalMillis)
        assertTrue(job.isPersisted) // survives a reboot
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
    }

    @Test fun `short intervals are stretched to android's 15 minutes`() {
        store.refreshIntervalMinutes = 5
        RankRefreshJob.schedule(app)
        assertEquals(15 * 60_000L, periodic()!!.intervalMillis)
    }

    @Test fun `a new interval re-times the job only while there is a widget`() {
        store.refreshIntervalMinutes = 60
        RankRefreshJob.reschedule(app)
        assertNull(periodic())
        shadowOf(AppWidgetManager.getInstance(app)).createWidget(RankWidget::class.java, R.layout.widget_wide)
        store.refreshIntervalMinutes = 3 * 60
        RankRefreshJob.reschedule(app)
        assertEquals(3 * 60 * 60_000L, periodic()!!.intervalMillis)
    }

    @Test fun `cancel removes the periodic job`() {
        RankRefreshJob.schedule(app)
        RankRefreshJob.cancel(app)
        assertNull(periodic())
    }

    @Test fun `a queued request runs as an expedited job that needs a network`() {
        RankRefreshJob.scheduleFetchNow(app)
        val job = jobs.getPendingJob(4046)!!
        assertTrue(job.isExpedited)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
        assertEquals(0L, job.intervalMillis) // once, not periodic
    }
}
