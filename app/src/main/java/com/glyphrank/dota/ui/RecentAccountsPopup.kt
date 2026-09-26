package com.glyphrank.dota.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.glyphrank.dota.data.RecentAccounts
import kotlin.math.abs
import kotlin.math.min

/**
 * The recent accounts as a dropdown under the input field: medal, name and rank per row.
 * Tap a row to pick it; tap ✕ or swipe a row sideways to remove it, if [isRemovable]. It
 * floats over the page and never takes the focus, so the keyboard stays with the input field.
 */
class RecentAccountsPopup(
    private val anchor: View,
    /** The account's last known medal as a matrix frame. */
    private val medalFor: (RecentAccounts.Entry) -> IntArray,
    /** The account's last known rank, e.g. "Legend 4", or null. */
    private val rankFor: (RecentAccounts.Entry) -> String?,
    private val onPick: (RecentAccounts.Entry) -> Unit,
    private val onRemove: (RecentAccounts.Entry) -> Unit,
    /** False for rows without ✕ and swipe, e.g. the saved account. */
    private val isRemovable: (RecentAccounts.Entry) -> Boolean = { true },
) {
    private val context = anchor.context
    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val list = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(SURFACE)
            cornerRadius = dp(12).toFloat()
        }
        clipToOutline = true
        setPadding(0, dp(4), 0, dp(4))
    }

    private val popup = PopupWindow(list, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
        isOutsideTouchable = true // a tap elsewhere closes it
        inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED // stays clear of the keyboard
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        elevation = dp(8).toFloat()
    }

    val isShowing: Boolean get() = popup.isShowing

    /** Shows [entries] (or updates the open dropdown); hides it if there are none. */
    fun show(entries: List<RecentAccounts.Entry>) {
        if (entries.isEmpty() || !anchor.isAttachedToWindow) return dismiss()
        list.removeAllViews()
        entries.forEach { list.addView(row(it)) }
        if (popup.isShowing) {
            popup.update(anchor, 0, dp(4), anchor.width, ViewGroup.LayoutParams.WRAP_CONTENT)
        } else {
            popup.width = anchor.width
            popup.showAsDropDown(anchor, 0, dp(4))
        }
    }

    fun dismiss() {
        if (popup.isShowing) popup.dismiss()
    }

    private fun row(entry: RecentAccounts.Entry): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(dp(12), dp(6), 0, dp(6))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(PRESSED))
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
        addView(MatrixPreviewView(context).apply { frame = medalFor(entry) }, LinearLayout.LayoutParams(dp(36), dp(36)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(entry.name ?: "Player ${entry.accountId}", 15f, Color.WHITE, bold = true))
            rankFor(entry)?.let { addView(label(it, 13f, MUTED)) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        val removable = isRemovable(entry)
        addView(if (removable) {
            label("✕", 16f, MUTED).apply {
                gravity = Gravity.CENTER
                contentDescription = "Remove ${entry.name ?: "account"}"
                setOnClickListener { onRemove(entry) }
            }
        } else {
            View(context) // keeps the names aligned
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        setOnTouchListener(SwipeOrTap(entry, removable))
    }

    /** Tap picks the row; a sideways swipe past a third of its width removes it (if [removable]). */
    private inner class SwipeOrTap(
        private val entry: RecentAccounts.Entry,
        private val removable: Boolean,
    ) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val dx = e.rawX - downX
            val dy = e.rawY - downY
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                    moved = false
                    v.isPressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        moved = true
                        v.isPressed = false
                    }
                    if (removable && !dragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                        dragging = true
                        v.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        v.translationX = dx
                        v.alpha = 1f - min(1f, abs(dx) / v.width)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    when {
                        dragging && abs(dx) > v.width / 3f ->
                            v.animate().translationX(if (dx > 0) v.width.toFloat() else -v.width.toFloat())
                                .alpha(0f).setDuration(150).withEndAction { onRemove(entry) }
                        dragging -> v.animate().translationX(0f).alpha(1f).setDuration(150)
                        !moved -> onPick(entry)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    v.animate().translationX(0f).alpha(1f).setDuration(150)
                }
            }
            return true
        }
    }

    private fun label(value: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun dp(v: Int) = (v * density).toInt()

    private companion object {
        val SURFACE = Color.rgb(0x1C, 0x1C, 0x1C)
        val PRESSED = Color.rgb(0x2A, 0x2A, 0x2A)
        val MUTED = Color.rgb(0x8A, 0x8A, 0x8A)
    }
}
