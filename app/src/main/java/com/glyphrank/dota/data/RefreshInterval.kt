package com.glyphrank.dota.data

/**
 * How old the cached rank may get before the toy fetches it again on its own
 * (on selection or an AOD tick). Manual refreshes (long-press, "Save & check rank")
 * are not affected.
 */
object RefreshInterval {
    const val MIN_MINUTES = 5
    const val MAX_MINUTES = 24 * 60
    const val DEFAULT_MINUTES = 30

    /** Options offered on the settings screen. */
    val CHOICES_MINUTES = listOf(5, 15, 30, 60, 3 * 60, 6 * 60, 12 * 60, 24 * 60)

    fun clamp(minutes: Int): Int = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)

    fun toMillis(minutes: Int): Long = clamp(minutes) * 60_000L

    fun isStale(fetchedAtMs: Long, nowMs: Long, minutes: Int): Boolean = nowMs - fetchedAtMs > toMillis(minutes)

    fun label(minutes: Int): String = when {
        minutes >= MAX_MINUTES -> "Once a day"
        minutes >= 60 -> "Every ${minutes / 60} h"
        else -> "Every $minutes min"
    }
}
