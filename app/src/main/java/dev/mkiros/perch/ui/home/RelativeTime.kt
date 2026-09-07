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
 *
 * S06 (issue #25) added the guess marker. `EntryEntity.publishedIsEstimated` had been
 * stored since v0.1 and read by nothing: a date Perch invented — a feed item with no
 * date, or a pasted link whose page published none, both stamped with the moment of the
 * fetch — rendered exactly like one the author wrote. It now reads `~5h`, and the rule
 * lives here alone so that the row, the article byline and the reach sentence cannot
 * disagree about what a guess looks like.
 *
 * **A tilde is a glyph, not a word**, which is why marking a guess does not depart from
 * the paragraph above: there is nothing here to translate, so dates stay out of
 * `res/strings.xml` exactly as before. A word like "about" would have been a different
 * decision.
 */
object RelativeTime {

    private val dayAndMonth = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

    /**
     * @param isEstimated true when [publishedAt] is Perch's guess rather than the
     *   article's own date, in which case the result carries a leading [GUESS] marker.
     */
    fun format(
        publishedAt: Long,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        isEstimated: Boolean = false,
    ): String {
        val elapsed = now - publishedAt
        val date = when {
            // A feed's clock may be slightly ahead of ours; that is not the future.
            elapsed < MINUTE -> "now"
            elapsed < HOUR -> "${elapsed / MINUTE}min"
            elapsed < DAY -> "${elapsed / HOUR}h"
            elapsed < WEEK -> "${elapsed / DAY}d"
            else -> absolute(publishedAt, now, zone)
        }
        return if (isEstimated) "$GUESS$date" else date
    }

    /** Older than a week: the date, with a year only when it is not the current one. */
    private fun absolute(publishedAt: Long, now: Long, zone: ZoneId): String {
        val published = Instant.ofEpochMilli(publishedAt).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone)
        val formatter = if (published.year == today.year) dayAndMonth else dayMonthYear
        return formatter.format(published)
    }

    /** Shared with every other renderer of a guessed date, so they read alike. */
    const val GUESS = "~"

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val WEEK = 7 * DAY
}
