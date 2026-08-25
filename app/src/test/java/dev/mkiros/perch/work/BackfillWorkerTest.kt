package dev.mkiros.perch.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.EntryDao
import dev.mkiros.perch.data.db.FeedDao
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.parse.FetchedPage
import dev.mkiros.perch.data.parse.PageFetcher
import dev.mkiros.perch.data.repo.BackfillRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The worker translation for Z02's backfill — [BackfillRepository] already owns every
 * §0.3 clause ([dev.mkiros.perch.data.repo.BackfillRepositoryTest]); this only checks the
 * wiring WorkManager depends on: the input `feedId` reaches the repository, and a run that
 * finds nothing still succeeds rather than asking to be retried forever.
 */
@RunWith(RobolectricTestRunner::class)
class BackfillWorkerTest {

    private lateinit var db: PerchDatabase
    private lateinit var feeds: FeedDao
    private lateinit var entries: EntryDao
    private lateinit var fetcher: FakeFetcher
    private lateinit var backfill: BackfillRepository

    private val now = Instant.parse("2026-08-24T12:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        db = PerchDatabase.inMemory(ApplicationProvider.getApplicationContext())
        feeds = db.feedDao()
        entries = db.entryDao()
        fetcher = FakeFetcher()
        backfill = BackfillRepository(
            feedDao = feeds,
            entryDao = entries,
            fetcher = fetcher,
            clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
            delay = { },
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `an unknown feedId succeeds without asking to be retried`() {
        val result = runWorker(feedId = 999)

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun `a feed with no archive at all still succeeds`() {
        val feedId = runBlocking { addFeed() }

        val result = runWorker(feedId)

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(runBlocking { entries.countAll() }).isEqualTo(0)
    }

    @Test
    fun `no feedId in the input data fails rather than crashing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = TestListenableWorkerBuilder<BackfillWorker>(context)
            .setWorkerFactory(PerchWorkerFactory(feeds = { error("not needed") }, backfill = { backfill }))
            .build()

        val result = runBlocking { worker.doWork() }

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
    }

    // ---- harness ------------------------------------------------------------------

    private fun runWorker(feedId: Long): ListenableWorker.Result {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = TestListenableWorkerBuilder<BackfillWorker>(context)
            .setInputData(workDataOf(BackfillWorker.KEY_FEED_ID to feedId))
            .setWorkerFactory(PerchWorkerFactory(feeds = { error("not needed") }, backfill = { backfill }))
            .build()
        return runBlocking { worker.doWork() }
    }

    private suspend fun addFeed(): Long = feeds.insert(
        FeedEntity(
            feedUrl = "https://example.com/feed.xml",
            siteUrl = "https://example.com",
            title = "A blog",
            customTitle = null,
            faviconUrl = null,
            etag = null,
            lastModified = null,
            lastFetchedAt = now,
            lastSuccessAt = now,
            lastError = null,
            addedAt = now,
        ),
    )

    private class FakeFetcher : PageFetcher {
        override suspend fun fetch(url: String): FetchedPage? = null
    }
}
