package dev.mkiros.perch.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.ui.home.TimeFilter
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #9 / V02: the container is where "now" gets its zone, and it was Greenwich's.
 *
 * The bug was one default argument up from the window: the container injected
 * `Clock.systemUTC()`, whose zone is UTC, so west of Greenwich the Feed emptied every
 * evening once local time passed UTC midnight (19:00 CDT / 18:00 CST) — U07's calendar
 * "Today" opened at a moment later than everything the reader's day had published.
 *
 * W02/#15 retired the calendar window, so the zone can no longer decide what is *in* the
 * list; what it still decides is every date a human reads (`RelativeTime`, the article
 * byline), which is why the container keeps `systemDefaultZone()` and this test keeps
 * asserting it. The window is asserted here too, in the shape it has now: twenty-four
 * hours back from the container's own instant, whatever zone that clock carries.
 *
 * The JVM's default zone is pinned here rather than inherited: a test that reads whatever
 * zone the machine happens to be in cannot tell UTC-the-bug from UTC-the-build-agent.
 */
@RunWith(RobolectricTestRunner::class)
class AppContainerClockTest {

    @Test
    fun `the container's clock carries the device's zone, not UTC`() {
        inZone(CHICAGO) {
            withContainer { container ->
                assertThat(container.clock.zone).isEqualTo(ZoneId.of(CHICAGO))
            }
        }
    }

    @Test
    fun `today opens twenty-four hours back from the container's own now`() {
        inZone(CHICAGO) {
            withContainer { container ->
                val since = TimeFilter.Today.since(container.clock)!!
                val now = Instant.now(container.clock).toEpochMilli()

                // An entry 23 h old is in the window the container hands the list; one
                // 25 h old is out. Stated against the container rather than a fixture
                // clock because the default argument is what issue #9 got wrong.
                assertThat(now - HOUR * 23).isAtLeast(since)
                assertThat(now - HOUR * 25).isLessThan(since)
                // A tolerance, not an equality: the container's clock is the real one, so
                // `since` and `now` are two reads of it and a tick can land between them.
                assertThat(now - since).isIn(com.google.common.collect.Range.closed(HOUR * 24, HOUR * 24 + 5_000))
            }
        }
    }

    private fun inZone(id: String, body: () -> Unit) {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(id))
        try {
            body()
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    /** Builds a container the way production does — taking the clock's default. */
    private fun withContainer(body: (AppContainer) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = AppContainer(
            database = PerchDatabase.inMemory(context),
            httpClient = PerchHttp.client(cacheDir = null),
        )
        try {
            body(container)
        } finally {
            container.close()
        }
    }

    private companion object {
        const val CHICAGO = "America/Chicago"
        const val HOUR = 60 * 60 * 1000L
    }
}
