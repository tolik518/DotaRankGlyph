package com.glyphrank.dota.widget

import android.app.job.JobScheduler
import android.appwidget.AppWidgetManager
import android.widget.ImageView
import com.glyphrank.dota.R
import com.glyphrank.dota.data.MockHttpServer.Response
import com.glyphrank.dota.data.TestRepository
import com.glyphrank.dota.data.TestRepository.Companion.player
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** The home-screen widget as the launcher would show it, fed by the real repository. */
@RunWith(RobolectricTestRunner::class)
class RankWidgetTest {
    private val app = RuntimeEnvironment.getApplication()
    private val env = TestRepository(app)
    private val widgets = shadowOf(AppWidgetManager.getInstance(app))
    private val jobs = app.getSystemService(JobScheduler::class.java)
    private val zq = 116233682L

    @After fun tearDown() = env.close()

    private fun addWidget(): Int = widgets.createWidget(RankWidget::class.java, R.layout.widget_wide)

    /**
     * The rank as the widget shows it. Robolectric lays out the smallest size, where the rank is
     * the medal's content description; the texts of the wide size are tested in [WidgetContentTest].
     */
    private fun rank(widgetId: Int): String? =
        widgets.getViewFor(widgetId).findViewById<ImageView>(R.id.widget_medal).contentDescription?.toString()

    @Test fun `without an ID the widget asks for one`() {
        assertEquals("Set your ID in the app", rank(addWidget()))
    }

    @Test fun `a saved rank appears on every widget`() {
        val first = addWidget()
        val second = addWidget()
        env.server.handler = { Response(200, player(zq, "ZQuixotix", 80, 2488)) }
        env.repo.setAccount(zq)
        assertEquals("Not checked yet", rank(first))
        env.repo.refresh(manual = true)
        env.finishRequests()
        assertEquals("Immortal #2488", rank(first))
        assertEquals("Immortal #2488", rank(second))
    }

    @Test fun `adding a widget starts the background refresh, removing the last one stops it`() {
        assertFalse(RankWidget.hasWidgets(app))
        assertNull(jobs.getPendingJob(4045))
        addWidget()
        assertTrue(RankWidget.hasWidgets(app))
        assertNotNull(jobs.getPendingJob(4045))
        RankWidget().onDisabled(app) // the launcher sends this after the last one is removed
        assertNull(jobs.getPendingJob(4045))
    }
}
