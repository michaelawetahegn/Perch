package dev.mkiros.perch.ui.home

import java.time.Clock
import java.time.Duration

/**
 * How far back the reading list reaches (PLAN-4 §0, W02/#15).
 *
 * Time is a *filter* and folder is a *scope* (W03); they are two different dimensions and
 * this is the filter half. It is a **rolling** window, not a calendar one: "Today" opens
 * twenty-four hours before the moment the query runs, so an article published at 23:59
 * last night is still today's at 00:30. U07 had it the other way — the window opened at
 * local midnight — and the reader reported what that costs: just after midnight, which is
 * exactly when they look, the Feed is empty because the day it is measuring has barely
 * started. A window that slides is a window that always has something in it.
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
     * Pure instant arithmetic: a duration back from [clock]'s instant. The clock's zone is
     * deliberately not read. It used to decide which midnight the window opened at, and
     * getting that wrong was issue #9; a rolling window cannot regress that way because
     * there is no midnight in it to belong to the wrong zone. The zone still matters to
     * everything a human *reads* — `RelativeTime`'s dates, the article byline — which is
     * why `AppContainer` still injects `systemDefaultZone()` and V02 is not reverted.
     */
    fun since(clock: Clock): Long? {
        val reach = when (this) {
            Today -> Duration.ofHours(24)
            PastWeek -> Duration.ofDays(7)
            PastMonth -> Duration.ofDays(30)
            PastYear -> Duration.ofDays(365)
            AllTime -> return null
        }
        return clock.instant().minus(reach).toEpochMilli()
    }

    /** The next window out, for the empty bucket's widen affordance. Null past All Time. */
    val wider: TimeFilter? get() = entries.getOrNull(ordinal + 1)

    companion object {
        /** What home opens on, and what a fresh install reads back from DataStore. */
        val Default = Today
    }
}
