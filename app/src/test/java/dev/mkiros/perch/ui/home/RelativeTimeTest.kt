package dev.mkiros.perch.ui.home

import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test

/**
 * The metadata line's clock (DESIGN.md §5, U08): coarser the further back an entry sits,
 * and **compact** — the row says `47min`, not `47 minutes ago`. The reference row
 * (`design/reference/feed-row-reference.jpg`) sets `Source / 5h` on one line beside a
 * thumbnail, and "ago" is a word the position of the text already implies.
 */
class RelativeTimeTest {

    private val utc = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 8, 7, 12, 0, 0, 0, utc).toInstant().toEpochMilli()

    @Test
    fun `something published seconds ago reads as now`() {
        assertThat(format(now - 30 * SECOND)).isEqualTo("now")
    }

    @Test
    fun `a feed clock running ahead of ours is not the future`() {
        assertThat(format(now + 5 * MINUTE)).isEqualTo("now")
    }

    @Test
    fun `the last hour is counted in minutes`() {
        assertThat(format(now - 5 * MINUTE)).isEqualTo("5min")
        assertThat(format(now - 47 * MINUTE)).isEqualTo("47min")
        assertThat(format(now - 59 * MINUTE)).isEqualTo("59min")
    }

    @Test
    fun `the last day is counted in hours`() {
        assertThat(format(now - HOUR)).isEqualTo("1h")
        assertThat(format(now - 5 * HOUR)).isEqualTo("5h")
        assertThat(format(now - 23 * HOUR)).isEqualTo("23h")
    }

    @Test
    fun `the last week is counted in days`() {
        assertThat(format(now - DAY)).isEqualTo("1d")
        assertThat(format(now - 3 * DAY)).isEqualTo("3d")
        assertThat(format(now - 6 * DAY)).isEqualTo("6d")
    }

    @Test
    fun `beyond a week ago becomes a date`() {
        assertThat(format(now - 8 * DAY)).isEqualTo("30 Jul")
    }

    @Test
    fun `a date in an earlier year carries its year`() {
        assertThat(format(now - 400 * DAY)).isEqualTo("3 Jul 2025")
    }

    private fun format(publishedAt: Long) = RelativeTime.format(publishedAt, now, utc)

    private companion object {
        const val SECOND = 1_000L
        const val MINUTE = 60 * SECOND
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
