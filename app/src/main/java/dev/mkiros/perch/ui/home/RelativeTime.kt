package dev.mkiros.perch.ui.home

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The `/ 5h` half of a row's metadata line (DESIGN.md §5).
 *
 * Deliberately a pure function of two instants rather than a composable reading a
 * `Clock`: the moment "now" is decided belongs to the state that produced the list, so a
 * screenshot test and a unit test can both pin it. It is also why this lives outside
 * `res/strings.xml` — Perch v1 ships English only, and the alternative (threading a
 * `Context` into every row) buys nothing while the app has one locale.
 *
 * The scale coarsens the way memory does: minutes for the last hour, hours for the last
 * day, days for the last week, then an actual date. "Ago" stops carrying information
 * somewhere around a week.
 *
 * U08 made it **compact** — `47min`, `5h`, `1d` — to match
 * `design/reference/feed-row-reference.jpg`. The word "ago" was carrying no information
 * that `Source / 5h` on a reading list does not already imply, and it was doing so on the
 * one line that has to survive a long source name beside a 96dp thumbnail.
 */
object RelativeTime {

    private val dayAndMonth = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

    fun format(publishedAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val elapsed = now - publishedAt
        return when {
            // A feed's clock may be slightly ahead of ours; that is not the future.
            elapsed < MINUTE -> "now"
            elapsed < HOUR -> "${elapsed / MINUTE}min"
            elapsed < DAY -> "${elapsed / HOUR}h"
            elapsed < WEEK -> "${elapsed / DAY}d"
            else -> absolute(publishedAt, now, zone)
        }
    }

    /** Older than a week: the date, with a year only when it is not the current one. */
    private fun absolute(publishedAt: Long, now: Long, zone: ZoneId): String {
        val published = Instant.ofEpochMilli(publishedAt).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone)
        val formatter = if (published.year == today.year) dayAndMonth else dayMonthYear
        return formatter.format(published)
    }

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val WEEK = 7 * DAY
}
