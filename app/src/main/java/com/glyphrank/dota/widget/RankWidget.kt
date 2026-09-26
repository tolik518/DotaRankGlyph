package com.glyphrank.dota.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.util.SizeF
import android.widget.RemoteViews
import com.glyphrank.dota.R
import com.glyphrank.dota.data.BundledMedals
import com.glyphrank.dota.data.RankStore
import com.glyphrank.dota.ui.MainActivity
import com.glyphrank.dota.ui.MatrixPainter

/**
 * Home-screen widget: the current medal as Glyph Matrix dots, and in wider sizes the name,
 * rank and update time. Tap opens the app. [RankRefreshJob] refreshes the rank in the
 * background while a widget exists; every saved rank updates the widgets.
 */
class RankWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
        RankRefreshJob.schedule(context)
    }

    override fun onEnabled(context: Context) = RankRefreshJob.schedule(context)

    override fun onDisabled(context: Context) = RankRefreshJob.cancel(context)

    companion object {
        private const val MEDAL_PX = 256

        fun hasWidgets(context: Context): Boolean = ids(context).isNotEmpty()

        /** Redraws every widget from the cached rank. */
        fun updateAll(context: Context) {
            val ids = ids(context)
            if (ids.isNotEmpty()) render(context, AppWidgetManager.getInstance(context), ids)
        }

        private fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, RankWidget::class.java))

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val content = WidgetContent.of(RankStore(context), BundledMedals.load(context))
            val medal = MatrixPainter.bitmap(content.frame, MEDAL_PX)
            val updated = content.fetchedAtMs?.let { "Updated ${time(context, it)}" } ?: ""

            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val small = RemoteViews(context.packageName, R.layout.widget_small).apply {
                setImageViewBitmap(R.id.widget_medal, medal)
                setContentDescription(R.id.widget_medal, content.rank)
                setOnClickPendingIntent(R.id.widget_root, open)
            }
            val wide = RemoteViews(context.packageName, R.layout.widget_wide).apply {
                setImageViewBitmap(R.id.widget_medal, medal)
                setContentDescription(R.id.widget_medal, content.rank)
                setTextViewText(R.id.widget_name, content.name)
                setTextViewText(R.id.widget_rank, content.rank)
                setTextViewText(R.id.widget_updated, updated)
                setOnClickPendingIntent(R.id.widget_root, open)
            }
            // The launcher picks the largest layout that fits the widget's size.
            val views = RemoteViews(mapOf(SizeF(40f, 40f) to small, SizeF(150f, 40f) to wide))
            manager.updateAppWidget(ids, views)
        }

        /** "14:05" today, otherwise "24 Sep". */
        private fun time(context: Context, ms: Long): String =
            if (DateUtils.isToday(ms)) DateFormat.getTimeFormat(context).format(ms)
            else DateUtils.formatDateTime(context, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    }
}
