package dev.mkiros.perch.model

import java.time.Duration

/**
 * How often the background pass runs, per SPEC.md §7. The values Settings offers.
 *
 * Fifteen minutes is WorkManager's own floor for periodic work, so it is also ours.
 */
enum class RefreshInterval(
    /** null means "never in the background" — [Manual] is refresh-when-I-pull. */
    val duration: Duration?,
) {
    Manual(null),
    Every15Minutes(Duration.ofMinutes(15)),
    Hourly(Duration.ofHours(1)),
    Every3Hours(Duration.ofHours(3)),
    Every6Hours(Duration.ofHours(6)),
    ;

    companion object {
        /** What a fresh install polls at until the reader says otherwise. */
        val Default = Hourly
    }
}
