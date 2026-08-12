package com.arunrk.note.feature.notes.component

import com.arunrk.note.core.common.platform.currentTimeMillis
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Human-readable timestamp for a note card.
 *
 * Recent edits read better as a relative time ("2 hours ago") because that is
 * how people think about their own recent activity; anything older switches to
 * an absolute date, because "47 days ago" is a number nobody can picture.
 */
fun relativeTime(epochMillis: Long, now: Long = currentTimeMillis()): String {
    val elapsed = now - epochMillis

    // A clock change or a note edited on a device running ahead can produce a
    // timestamp in the future. Showing "in 3 hours" for something the user just
    // typed looks broken, so treat it as just now.
    if (elapsed < 0) return "Just now"

    return when {
        elapsed < MINUTE -> "Just now"
        elapsed < HOUR -> "${elapsed / MINUTE} min ago"
        elapsed < DAY -> pluralise(elapsed / HOUR, "hour")
        elapsed < WEEK -> pluralise(elapsed / DAY, "day")
        else -> absoluteDate(epochMillis)
    }
}

private fun pluralise(count: Long, unit: String): String =
    if (count == 1L) "1 $unit ago" else "$count ${unit}s ago"

private fun absoluteDate(epochMillis: Long): String = runCatching {
    val dateTime = Instant.fromEpochMilliseconds(epochMillis)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    "${dateTime.dayOfMonth} ${MONTHS[dateTime.monthNumber - 1]}"
}.getOrDefault("")

private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR
private const val WEEK = 7 * DAY
