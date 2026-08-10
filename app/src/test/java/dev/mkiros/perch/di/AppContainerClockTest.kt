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
 * `TimeFilter.since` was always right — it opens Today at midnight *in the clock's zone*.
 * The bug was one default argument up: the container injected `Clock.systemUTC()`, whose
 * zone is UTC, so west of Greenwich the Feed emptied every evening once local time passed
 * UTC midnight (19:00 CDT / 18:00 CST) — "Today" opened at a moment later than everything
 * the reader's day had published.
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
    fun `today opens at the reader's midnight, five hours after Greenwich's`() {
        inZone(CHICAGO) {
            withContainer { container ->
                val zone = ZoneId.of(CHICAGO)
                val readersMidnight = Instant.now(container.clock).atZone(zone)
                    .toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

                assertThat(TimeFilter.Today.since(container.clock)).isEqualTo(readersMidnight)
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
    }
}
