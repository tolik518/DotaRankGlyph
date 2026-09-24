package com.glyphrank.dota.ui

import android.content.Context
import android.text.format.DateFormat

/** Short time texts for the status line. */
object TimeText {
    private const val MINUTE_MS = 60_000L

    /** "just now", "12 min ago", "3 h ago", "2 days ago". */
    fun ago(thenMs: Long, nowMs: Long): String {
        val minutes = (nowMs - thenMs).coerceAtLeast(0) / MINUTE_MS
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 48 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }

    /** Local wall-clock time in the phone's 12/24 h format, e.g. "14:05". */
    fun clock(context: Context, ms: Long): String = DateFormat.getTimeFormat(context).format(ms)
}
