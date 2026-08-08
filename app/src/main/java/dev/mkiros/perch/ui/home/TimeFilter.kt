package dev.mkiros.perch.ui.home

import java.time.Clock
import java.time.LocalDate

/**
 * How far back the reading list reaches (PLAN-2 §0, U07).
 *
 * Time is a *filter* and folder is a *section*; they are two different dimensions and
 * this is the filter half. It is a **calendar** window, not an arithmetic one: "Today"
 * opens at local midnight, so an article published at 23:59 last night is not today's at
 * 00:30 even though it is well inside the last twenty-four hours. Readers think in days,
 * and a window that slides through the night puts articles back that they finished with.
 *
 * Declared narrowest first, which is the order the dropdown offers them in and the order
 * [wider] walks — so the empty state's "show more" affordance is one step, never a menu.
 *
 * The window applies to home alone. To-Read and Liked ignore it: a to-read list that
 * hides last month's articles is not a to-read list (§0).
 */
enum class TimeFilter {
    Today,
    PastWeek,
    PastMonth,
    PastYear,
    AllTime,
    ;

    /**
     * The opening edge of the window as epoch millis — inclusive — or null for All Time,
     * which has no edge at all rather than one at the epoch.
     *
     * The day is [clock]'s zone's day. A `Clock` carries its own zone, so injecting one
     * fixes both "now" and "which midnight" together and a test cannot pin one without
     * the other.
     */
    fun since(clock: Clock): Long? {
        val today = LocalDate.now(clock)
        val opens = when (this) {
            Today -> today
            PastWeek -> today.minusWeeks(1)
            PastMonth -> today.minusMonths(1)
            PastYear -> today.minusYears(1)
            AllTime -> return null
        }
        return opens.atStartOfDay(clock.zone).toInstant().toEpochMilli()
    }

    /** The next window out, for the empty bucket's widen affordance. Null past All Time. */
    val wider: TimeFilter? get() = entries.getOrNull(ordinal + 1)

    companion object {
        /** What home opens on, and what a fresh install reads back from DataStore. */
        val Default = Today
    }
}
