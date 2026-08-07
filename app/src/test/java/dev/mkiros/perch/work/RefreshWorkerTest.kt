package dev.mkiros.perch.work

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.EntryDao
import dev.mkiros.perch.data.db.FeedDao
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.net.FeedFetcher
import dev.mkiros.perch.data.repo.FeedRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The background pass: what the worker collects, and what it tells WorkManager afterwards.
 *
 * A periodic refresh is the one part of the app nobody is watching, so the interesting
 * assertions are about restraint — a source that has failed five times in a row is not
 * hammered every hour (SPEC.md §7), and a pass where everything failed asks to be retried
 * rather than quietly counting as done.
 */
@RunWith(RobolectricTestRunner::class)
class RefreshWorkerTest {

    private lateinit var db: PerchDatabase
    private lateinit var feeds: FeedDao
    private lateinit var entries: EntryDao
    private lateinit var server: MockWebServer
    private lateinit var repo: FeedRepository

    private val now = Instant.parse("2026-08-07T12:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PerchDatabase::class.java,
        ).build()
        feeds = db.feedDao()
        entries = db.entryDao()
        server = MockWebServer()
        server.start()
        repo = FeedRepository(
            feedDao = feeds,
            entryDao = entries,
            fetcher = FeedFetcher(
                OkHttpClient.Builder().readTimeout(500, TimeUnit.MILLISECONDS).build(),
            ),
            clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    @Test
    fun `a periodic pass fetches the subscribed feeds and stores their entries`() {
        server.enqueue(ok(rss(item("a1"), item("a2"))))
        runBlocking { addFeed() }

        val result = runWorker()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(runBlocking { entries.countAll() }).isEqualTo(2)
    }

    @Test
    fun `a pass with nothing subscribed succeeds without asking to be retried`() {
        val result = runWorker()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `one healthy source carries a pass in which another one failed`() {
        server.dispatcher = pathDispatcher("/sick.xml" to MockResponse().setResponseCode(500))
        runBlocking {
            addFeed("/well.xml")
            addFeed("/sick.xml")
        }

        val result = runWorker()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(runBlocking { entries.countAll() }).isEqualTo(1)
    }

    @Test
    fun `a pass in which every source failed asks WorkManager to retry`() {
        server.enqueue(MockResponse().setResponseCode(500))
        runBlocking { addFeed() }

        val result = runWorker()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun `retrying stops after three attempts and waits for the next period instead`() {
        server.enqueue(MockResponse().setResponseCode(500))
        runBlocking { addFeed() }

        val result = runWorker(runAttemptCount = 3)

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    // ---- the 6h floor for sick sources (SPEC.md §7) -----------------------------

    @Test
    fun `a source that has failed five times in a row is left alone until six hours pass`() {
        runBlocking { addFeed(failures = 5, lastFetchedAt = now - Duration.ofHours(5).toMillis()) }

        val result = runWorker()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a source held back by the six-hour floor is tried again once it expires`() {
        server.enqueue(ok(rss(item("a1"))))
        runBlocking { addFeed(failures = 5, lastFetchedAt = now - Duration.ofHours(7).toMillis()) }

        runWorker()

        assertThat(server.requestCount).isEqualTo(1)
        assertThat(runBlocking { entries.countAll() }).isEqualTo(1)
    }

    @Test
    fun `four failures in a row is not yet sick enough to be held back`() {
        server.enqueue(ok(rss(item("a1"))))
        runBlocking { addFeed(failures = 4, lastFetchedAt = now - Duration.ofMinutes(5).toMillis()) }

        runWorker()

        assertThat(server.requestCount).isEqualTo(1)
    }

    // ---- harness ----------------------------------------------------------------

    private fun runWorker(runAttemptCount: Int = 0): ListenableWorker.Result {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = TestListenableWorkerBuilder<RefreshWorker>(context)
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(PerchWorkerFactory { repo })
            .build()
        return runBlocking { worker.doWork() }
    }

    private suspend fun addFeed(
        path: String = "/feed.xml",
        failures: Int = 0,
        lastFetchedAt: Long? = null,
    ): Long = feeds.insert(
        FeedEntity(
            feedUrl = server.url(path).toString(),
            siteUrl = null,
            title = path,
            customTitle = null,
            faviconUrl = null,
            etag = null,
            lastModified = null,
            lastFetchedAt = lastFetchedAt,
            lastSuccessAt = null,
            lastError = if (failures > 0) "Nope." else null,
            consecutiveFailures = failures,
            addedAt = now,
        ),
    )

    private fun ok(body: String) = MockResponse()
        .setBody(body)
        .addHeader("Content-Type", "application/rss+xml; charset=utf-8")

    private fun pathDispatcher(vararg overrides: Pair<String, MockResponse>) =
        object : okhttp3.mockwebserver.Dispatcher() {
            private val byPath = overrides.toMap()
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                byPath[request.path] ?: ok(rss(item("a1")))
        }

    private fun rss(vararg items: String) =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <rss version="2.0"><channel>
          <title>Example Feed</title>
          <link>https://example.com/</link>
          ${items.joinToString("\n")}
        </channel></rss>
        """.trimIndent()

    private fun item(guid: String) =
        "<item><guid isPermaLink=\"false\">$guid</guid><title>$guid</title>" +
            "<link>https://example.com/$guid</link>" +
            "<pubDate>Mon, 03 Aug 2026 10:00:00 GMT</pubDate></item>"
}
