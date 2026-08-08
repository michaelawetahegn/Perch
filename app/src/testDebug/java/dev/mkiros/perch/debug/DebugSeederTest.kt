package dev.mkiros.perch.debug

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.net.FeedFetcher
import dev.mkiros.perch.data.repo.FeedRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The debug build's first run has to look like someone has been reading for a while —
 * T29 and T32 critique screenshots, and a critique of an empty state teaches nothing.
 *
 * So the contract is about content, not plumbing: every bundled snapshot becomes a source,
 * they bring enough entries to fill and scroll a reading list, and none of it costs a
 * request. The second half is that seeding is a *first-run* act — a reader who deleted a
 * seeded source must not find it back in the drawer at the next launch.
 */
@RunWith(RobolectricTestRunner::class)
class DebugSeederTest {

    private lateinit var db: PerchDatabase
    private lateinit var seeder: DebugSeeder
    private lateinit var feeds: FeedRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = PerchDatabase.inMemory(context)
        feeds = FeedRepository(
            feedDao = db.feedDao(),
            entryDao = db.entryDao(),
            // Seeding reads assets and nothing else. This fetcher is never asked for
            // anything — the tests below would hang on a network round trip if it were.
            fetcher = FeedFetcher(OkHttpClient()),
            clock = FIXED_CLOCK,
        )
        seeder = DebugSeeder(context.assets, feeds, FIXED_CLOCK)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `seeds every bundled snapshot as a source`() = runTest {
        assertThat(seeder.seedIfEmpty()).isEqualTo(8)
        assertThat(db.feedDao().getAll()).hasSize(8)
    }

    @Test
    fun `seeds enough entries to fill a reading list`() = runTest {
        seeder.seedIfEmpty()

        assertThat(db.entryDao().countAll()).isGreaterThan(50)
        assertThat(db.entryDao().observeUnreadCount().first()).isGreaterThan(50)
    }

    @Test
    fun `seeded sources keep the real feed url so a refresh polls the right place`() =
        runTest {
            seeder.seedIfEmpty()

            assertThat(db.feedDao().getAll().map { it.feedUrl })
                .contains("https://nullprogram.com/feed/")
            assertThat(db.feedDao().getAll().map { it.title }).doesNotContain("")
        }

    @Test
    fun `seeded entries are sanitized and summarized like fetched ones`() = runTest {
        seeder.seedIfEmpty()

        val entries = db.entryDao().observeAll().first()
        assertThat(entries.map { it.title }).doesNotContain("")
        assertThat(entries.count { !it.summary.isNullOrBlank() }).isGreaterThan(50)
        assertThat(entries.mapNotNull { it.contentHtml }.none { it.contains("<script") }).isTrue()
    }

    @Test
    fun `does nothing when the reader already has sources`() = runTest {
        seeder.seedIfEmpty()
        val before = db.entryDao().countAll()

        assertThat(seeder.seedIfEmpty()).isEqualTo(0)
        assertThat(db.feedDao().getAll()).hasSize(8)
        assertThat(db.entryDao().countAll()).isEqualTo(before)
    }

    private companion object {
        /** Fixed so `publishedAt`'s "no date in the feed" fallback is deterministic. */
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-07T12:00:00Z"), ZoneOffset.UTC)
    }
}
