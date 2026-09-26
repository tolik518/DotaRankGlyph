package com.glyphrank.dota.ui

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.glyphrank.dota.data.RecentAccounts
import com.glyphrank.dota.glyph.MatrixLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** The recent-accounts dropdown: rows, tap to pick, ✕ or a sideways swipe to remove. */
@RunWith(RobolectricTestRunner::class)
class RecentAccountsPopupTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val anchor = EditText(activity)
    private val picked = mutableListOf<Long>()
    private val removed = mutableListOf<Long>()
    private val popup: RecentAccountsPopup

    private val zq = RecentAccounts.Entry(116233682, "ZQuixotix")
    private val zeitboy = RecentAccounts.Entry(40453096, "Zeitboy")
    private val unnamed = RecentAccounts.Entry(5, null)

    init {
        activity.setContentView(LinearLayout(activity).apply { addView(anchor, ViewGroup.LayoutParams(600, 100)) })
        idle()
        popup = RecentAccountsPopup(
            anchor,
            medalFor = { IntArray(MatrixLayout.SIZE * MatrixLayout.SIZE) },
            rankFor = { if (it == zq) "Immortal #2488" else null },
            onPick = { picked += it.accountId },
            onRemove = { removed += it.accountId },
            isRemovable = { it != unnamed },
        )
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun window(): PopupWindow = shadowOf(activity.application).latestPopupWindow

    /** The rows of the open dropdown, laid out 600 px wide. */
    private fun rows(): List<ViewGroup> {
        val list = window().contentView as ViewGroup
        list.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        list.layout(0, 0, 600, list.measuredHeight)
        return (0 until list.childCount).map { list.getChildAt(it) as ViewGroup }
    }

    private fun texts(v: View): List<String> =
        if (v is ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else listOfNotNull((v as? TextView)?.text?.toString())

    private fun touch(row: View, vararg moves: Pair<Float, Float>, cancel: Boolean = false) {
        val t = SystemClock.uptimeMillis()
        fun event(action: Int, xy: Pair<Float, Float>) = MotionEvent.obtain(t, t, action, xy.first, xy.second, 0)
        row.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, moves.first()))
        for (xy in moves.drop(1)) row.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, xy))
        row.dispatchTouchEvent(event(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, moves.last()))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500)) // swipe animations
    }

    @Test fun `each account shows its name and rank, unnamed ones their ID`() {
        popup.show(listOf(zq, unnamed))
        assertTrue(popup.isShowing)
        val rows = rows()
        assertEquals(listOf("ZQuixotix", "Immortal #2488", "✕"), texts(rows[0]))
        assertEquals(listOf("Player 5"), texts(rows[1])) // not removable in these tests
    }

    @Test fun `showing again updates the open dropdown`() {
        popup.show(listOf(zq, zeitboy))
        popup.show(listOf(zeitboy))
        assertEquals(listOf("Zeitboy"), rows().map { texts(it).first() })
    }

    @Test fun `no accounts closes it`() {
        popup.show(listOf(zq))
        popup.show(emptyList())
        assertFalse(popup.isShowing)
    }

    @Test fun `a tap picks the account`() {
        popup.show(listOf(zq, zeitboy))
        touch(rows()[1], 100f to 20f)
        assertEquals(listOf(zeitboy.accountId), picked)
        assertTrue(removed.isEmpty())
    }

    @Test fun `the cross removes the account`() {
        popup.show(listOf(zq, zeitboy))
        val cross = (rows()[0].getChildAt(2))
        cross.performClick()
        assertEquals(listOf(zq.accountId), removed)
        assertTrue(picked.isEmpty())
    }

    @Test fun `a long sideways swipe removes the account, both ways`() {
        popup.show(listOf(zq, zeitboy))
        touch(rows()[0], 100f to 20f, 200f to 22f, 400f to 25f)
        touch(rows()[1], 500f to 20f, 400f to 20f, 150f to 20f)
        assertEquals(listOf(zq.accountId, zeitboy.accountId), removed)
        assertTrue(picked.isEmpty())
    }

    @Test fun `a short swipe snaps back without picking or removing`() {
        popup.show(listOf(zq))
        val row = rows()[0]
        touch(row, 100f to 20f, 150f to 20f, 200f to 20f)
        assertTrue(removed.isEmpty() && picked.isEmpty())
        assertEquals(0f, row.translationX)
        assertEquals(1f, row.alpha)
    }

    @Test fun `scrolling past a row neither picks nor removes it`() {
        popup.show(listOf(zq))
        touch(rows()[0], 100f to 20f, 102f to 60f, 104f to 120f)
        assertTrue(removed.isEmpty() && picked.isEmpty())
    }

    @Test fun `a row that can't be removed has no cross and doesn't swipe`() {
        popup.show(listOf(zq, unnamed))
        val row = rows()[1]
        assertEquals(listOf("Player 5"), texts(row))
        touch(row, 500f to 20f, 400f to 20f, 150f to 20f)
        assertTrue(removed.isEmpty() && picked.isEmpty()) // a swipe isn't a tap either
        assertEquals(0f, row.translationX)
        touch(row, 100f to 20f)
        assertEquals(listOf(unnamed.accountId), picked)
    }

    @Test fun `a cancelled touch snaps back`() {
        popup.show(listOf(zq))
        val row = rows()[0]
        touch(row, 100f to 20f, 400f to 20f, cancel = true)
        assertTrue(removed.isEmpty() && picked.isEmpty())
        assertEquals(0f, row.translationX)
    }
}
