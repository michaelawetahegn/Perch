package dev.mkiros.perch.ui.home

import com.google.common.truth.Truth.assertThat
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

/**
 * Home's time window (PLAN-4 §0, W02/#15).
 *
 * The window is a **rolling** one, measured back from the instant the query runs: "Today"
 * is the last twenty-four hours, not the calendar day. U07 had it the other way and the
 * reader said why that was wrong — a cutoff at local midnight empties the Feed just after
 * midnight, which is exactly when they look. So every case here is stated as a
 * subtraction from a fixed "now", and the boundary is asserted against `now - n` rather
 * than against midnight.
 *
 * Nothing here pins a zone any more, because the window no longer reads one: a duration
 * back from an instant is the same instant in every zone. The zone still decides what a
 * human *reads* (`RelativeTimeTest` owns issue #9's arithmetic now, on the date label),
 * and the container still injects a zoned clock — `AppContainerClockTest` pins that.
 */
class TimeFilterTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-08-07T14:30:00Z")

    @Test
    fun `today opens twenty-four hours ago, not at local midnight`() {
        val since = TimeFilter.Today.since(clockAt(now, zone))

        assertThat(since).isEqualTo(now.minus(Duration.ofHours(24)).toEpochMilli())
    }

    @Test
    fun `an entry published at 23_59 yesterday is still inside today at 00_30`() {
        // U07's counter-example, now the point: at half past midnight the reader has not
        // yet read last night's articles, and a calendar cutoff hides all of them.
        val justAfterMidnight = LocalInstant("2026-08-07T00:30:00", zone)
        val clock = clockAt(justAfterMidnight, zone)
        val lastNight = LocalInstant("2026-08-06T23:59:00", zone).toEpochMilli()

        assertThat(lastNight).isAtLeast(TimeFilter.Today.since(clock))
    }

    @Test
    fun `the boundary is exactly twenty-four hours wide`() {
        val clock = clockAt(now, zone)

        val twentyThreeHoursOld = now.minus(Duration.ofHours(23)).toEpochMilli()
        val twentyFiveHoursOld = now.minus(Duration.ofHours(25)).toEpochMilli()

        assertThat(twentyThreeHoursOld).isAtLeast(TimeFilter.Today.since(clock))
        assertThat(twentyFiveHoursOld).isLessThan(TimeFilter.Today.since(clock))
    }

    @Test
    fun `the edge itself is inside the window`() {
        // `since` is the *inclusive* opening edge, and the query is `publishedAt >= since`:
        // an entry published exactly twenty-four hours ago is the last one still in.
        val clock = clockAt(now, zone)
        val exactlyOnTheEdge = now.minus(Duration.ofHours(24)).toEpochMilli()

        assertThat(exactlyOnTheEdge).isAtLeast(TimeFilter.Today.since(clock))
    }

    @Test
    fun `the wider windows reach back whole durations from the same moment`() {
        val clock = clockAt(now, zone)

        assertThat(TimeFilter.PastWeek.since(clock))
            .isEqualTo(now.minus(Duration.ofDays(7)).toEpochMilli())
        assertThat(TimeFilter.PastMonth.since(clock))
            .isEqualTo(now.minus(Duration.ofDays(30)).toEpochMilli())
        assertThat(TimeFilter.PastYear.since(clock))
            .isEqualTo(now.minus(Duration.ofDays(365)).toEpochMilli())
    }

    @Test
    fun `the same instant opens the same window in every zone`() {
        // The whole zone question the calendar window had. A rolling window cannot
        // regress the way issue #9 did: there is no midnight in it to be Greenwich's.
        val berlin = TimeFilter.Today.since(clockAt(now, zone))
        val chicago = TimeFilter.Today.since(clockAt(now, ZoneId.of("America/Chicago")))
        val greenwich = TimeFilter.Today.since(clockAt(now, ZoneOffset.UTC))

        assertThat(chicago).isEqualTo(berlin)
        assertThat(greenwich).isEqualTo(berlin)
    }

    @Test
    fun `a window moves with the clock rather than jumping at midnight`() {
        val evening = LocalInstant("2026-08-07T23:00:00", zone)
        val anHourLater = evening.plus(Duration.ofHours(1))

        val before = TimeFilter.Today.since(clockAt(evening, zone))!!
        val after = TimeFilter.Today.since(clockAt(anHourLater, zone))!!

        assertThat(after - before).isEqualTo(Duration.ofHours(1).toMillis())
    }

    @Test
    fun `all time has no boundary at all`() {
        assertThat(TimeFilter.AllTime.since(clockAt(now, zone))).isNull()
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
    fun `the ranges are offered narrowest first, and today is the default`() {
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
    fun `each window contains the one before it`() {
        val clock = clockAt(now, zone)

        val edges = listOf(TimeFilter.Today, TimeFilter.PastWeek, TimeFilter.PastMonth, TimeFilter.PastYear)
            .map { it.since(clock)!! }

        assertThat(edges).isInStrictOrder(Comparator<Long> { a, b -> b.compareTo(a) })
    }

    private fun clockAt(instant: Instant, zone: ZoneId): Clock = Clock.fixed(instant, zone)

    @Suppress("FunctionName")
    private fun LocalInstant(local: String, zone: ZoneId): Instant =
        java.time.LocalDateTime.parse(local).atZone(zone).toInstant()
}
