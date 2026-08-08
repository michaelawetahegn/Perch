package dev.mkiros.perch.ui.home

import com.google.common.truth.Truth.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

/**
 * Home's time window (PLAN-2 §0, U07).
 *
 * The whole point of this type is that "Today" is a *calendar* answer, not an arithmetic
 * one: an article published at 23:59 last night is not "today" at 00:30, even though it
 * is well inside the last twenty-four hours. So every case here is stated as a wall-clock
 * instant in a named zone rather than as a subtraction, and the boundary is asserted
 * against midnight rather than against `now - n`.
 */
class TimeFilterTest {

    private val zone = ZoneId.of("Europe/Berlin")

    @Test
    fun `today opens at local midnight, not twenty-four hours ago`() {
        val filter = TimeFilter.Today

        val since = filter.since(clockAt("2026-08-07T14:30:00", zone))

        assertThat(since).isEqualTo(instantAt("2026-08-07T00:00:00", zone))
    }

    @Test
    fun `an entry published at 23_59 yesterday is out of today and in the past week`() {
        val clock = clockAt("2026-08-07T00:30:00", zone)
        val lastNight = instantAt("2026-08-06T23:59:00", zone)

        assertThat(lastNight).isLessThan(TimeFilter.Today.since(clock))
        assertThat(lastNight).isAtLeast(TimeFilter.PastWeek.since(clock))
    }

    @Test
    fun `the day boundary follows the clock's zone, not UTC`() {
        // 01:00 in Berlin is still 23:00 the previous day in UTC: a reader east of
        // Greenwich must not spend their morning looking at yesterday's bucket.
        val clock = clockAt("2026-08-07T01:00:00", zone)

        assertThat(TimeFilter.Today.since(clock)).isEqualTo(instantAt("2026-08-07T00:00:00", zone))
    }

    @Test
    fun `the wider windows reach back whole days, each from midnight`() {
        val clock = clockAt("2026-08-07T14:30:00", zone)

        assertThat(TimeFilter.PastWeek.since(clock))
            .isEqualTo(instantAt("2026-07-31T00:00:00", zone))
        assertThat(TimeFilter.PastMonth.since(clock))
            .isEqualTo(instantAt("2026-07-07T00:00:00", zone))
        assertThat(TimeFilter.PastYear.since(clock))
            .isEqualTo(instantAt("2025-08-07T00:00:00", zone))
    }

    @Test
    fun `all time has no boundary at all`() {
        assertThat(TimeFilter.AllTime.since(clockAt("2026-08-07T14:30:00", zone))).isNull()
    }

    @Test
    fun `every window but all time offers a wider one to widen into`() {
        assertThat(TimeFilter.Today.wider).isEqualTo(TimeFilter.PastWeek)
        assertThat(TimeFilter.PastWeek.wider).isEqualTo(TimeFilter.PastMonth)
        assertThat(TimeFilter.PastMonth.wider).isEqualTo(TimeFilter.PastYear)
        assertThat(TimeFilter.PastYear.wider).isEqualTo(TimeFilter.AllTime)
        assertThat(TimeFilter.AllTime.wider).isNull()
    }

    @Test
    fun `the chips are offered narrowest first, and today is the default`() {
        assertThat(TimeFilter.entries).containsExactly(
            TimeFilter.Today,
            TimeFilter.PastWeek,
            TimeFilter.PastMonth,
            TimeFilter.PastYear,
            TimeFilter.AllTime,
        ).inOrder()
        assertThat(TimeFilter.Default).isEqualTo(TimeFilter.Today)
    }

    @Test
    fun `a window is measured against the same zone a UTC clock reports`() {
        val clock = clockAt("2026-08-07T14:30:00", ZoneOffset.UTC)

        assertThat(TimeFilter.Today.since(clock))
            .isEqualTo(instantAt("2026-08-07T00:00:00", ZoneOffset.UTC))
    }

    private fun clockAt(local: String, zone: ZoneId): Clock =
        Clock.fixed(Instant.ofEpochMilli(instantAt(local, zone)), zone)

    private fun instantAt(local: String, zone: ZoneId): Long =
        java.time.LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()
}
