package dev.mkiros.perch.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.EntryDao
import dev.mkiros.perch.data.db.FeedDao
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.net.FeedFetcher
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The refresh contract: fetch → parse → dedupe → upsert → record the source's health.
 *
 * The assertions that matter are the ones about *not* doing things. Polling a feed that
 * has not changed must cost zero rows and must not disturb what the reader has read; one
 * sick source must not take the other three down with it; and a source that recovers must
 * stop showing its `⚠`. Everything else is bookkeeping.
 */
@RunWith(RobolectricTestRunner::class)
class FeedRepositoryTest {

    private lateinit var db: PerchDatabase
    private lateinit var feeds: FeedDao
    private lateinit var entries: EntryDao
    private lateinit var server: MockWebServer
    private lateinit var repo: FeedRepository

    /** Every timestamp the repository writes comes from here, so tests can assert *when*. */
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

    // ---- the happy path --------------------------------------------------------

    @Test
    fun `a first refresh stores the feed's entries and its own title`() = runTest {
        server.enqueue(ok(rss(item("a1", title = "First"), item("a2", title = "Second"))))
        val id = addFeed()

        val outcome = repo.refresh(id)

        assertThat(outcome).isEqualTo(FeedRefreshOutcome.Updated(newEntries = 2))
        assertThat(entries.countAll()).isEqualTo(2)
        val feed = feeds.findById(id)!!
        assertThat(feed.title).isEqualTo("Example Feed")
        assertThat(feed.siteUrl).isEqualTo("https://example.com/")
        assertThat(feed.lastSuccessAt).isEqualTo(now)
        assertThat(feed.lastError).isNull()
    }

    @Test
    fun `article content is stored sanitized with a plain-text summary`() = runTest {
        server.enqueue(
            ok(
                rss(
                    item(
                        "a1",
                        content = "<p>Hello <b>there</b></p><script>alert(1)</script>",
                    ),
                ),
            ),
        )
        val id = addFeed()

        repo.refresh(id)

        val entry = entries.findByGuid(id, "a1")!!
        assertThat(entry.contentHtml).doesNotContain("script")
        assertThat(entry.contentHtml).contains("<b>there</b>")
        assertThat(entry.summary).isEqualTo("Hello there")
    }

    @Test
    fun `an entry with no date of its own is dated from when it was fetched`() = runTest {
        server.enqueue(ok(rss(item("a1", date = null), channelDate = null)))
        val id = addFeed()

        repo.refresh(id)

        val entry = entries.findByGuid(id, "a1")!!
        assertThat(entry.publishedAt).isEqualTo(now)
        assertThat(entry.publishedIsEstimated).isTrue()
    }

    @Test
    fun `two items sharing a guid collapse to one row`() = runTest {
        server.enqueue(ok(rss(item("a1", title = "Original"), item("a1", title = "Repost"))))
        val id = addFeed()

        val outcome = repo.refresh(id)

        assertThat(outcome).isEqualTo(FeedRefreshOutcome.Updated(newEntries = 1))
        assertThat(entries.findByGuid(id, "a1")!!.title).isEqualTo("Original")
    }

    @Test
    fun `a rename that lands mid-fetch survives the refresh`() = runTest {
        val id = addFeed()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                runBlocking { feeds.setCustomTitle(id, "My Name For It") }
                return ok(rss(item("a1")))
            }
        }

        repo.refresh(id)

        val feed = feeds.findById(id)!!
        assertThat(feed.customTitle).isEqualTo("My Name For It")
        assertThat(feed.title).isEqualTo("Example Feed")
    }

    // ---- refetching is a no-op -------------------------------------------------

    @Test
    fun `refreshing the same snapshot twice inserts no new rows and keeps read state`() =
        runTest {
            val snapshot = corpusSnapshot("ciechanow-ski.xml")
            server.enqueue(ok(snapshot))
            server.enqueue(ok(snapshot))
            val id = addFeed()

            val first = repo.refresh(id) as FeedRefreshOutcome.Updated
            assertThat(first.newEntries).isGreaterThan(0)
            val afterFirst = entries.countAll()
            val readId = entries.unreadIds(id).first()
            entries.setRead(listOf(readId), isRead = true, readAt = now)

            val second = repo.refresh(id)

            assertThat(second).isEqualTo(FeedRefreshOutcome.Updated(newEntries = 0))
            assertThat(entries.countAll()).isEqualTo(afterFirst)
            assertThat(entries.findById(readId)!!.isRead).isTrue()
        }

    @Test
    fun `a 304 is a success that parses nothing and writes no entries`() = runTest {
        server.enqueue(
            ok(rss(item("a1"))).addHeader("ETag", "\"v1\""),
        )
        server.enqueue(MockResponse().setResponseCode(304))
        val id = addFeed()
        repo.refresh(id)

        val outcome = repo.refresh(id)

        assertThat(outcome).isEqualTo(FeedRefreshOutcome.Unchanged)
        assertThat(entries.countAll()).isEqualTo(1)
        server.takeRequest()
        assertThat(server.takeRequest().getHeader("If-None-Match")).isEqualTo("\"v1\"")
        assertThat(feeds.findById(id)!!.lastFetchedAt).isEqualTo(now)
    }

    @Test
    fun `a redirect records the address to poll from now on`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(301).addHeader("Location", "/moved.xml"),
        )
        server.enqueue(ok(rss(item("a1"))))
        val id = addFeed()

        repo.refresh(id)

        assertThat(feeds.findById(id)!!.feedUrl).isEqualTo(server.url("/moved.xml").toString())
    }

    // ---- per-source failure isolation ------------------------------------------

    @Test
    fun `one failing feed does not stop the other three`() = runTest {
        server.dispatcher = pathDispatcher(
            "/sick.xml" to MockResponse().setResponseCode(500),
        )
        val sick = addFeed("/sick.xml")
        val healthy = listOf(addFeed("/a.xml"), addFeed("/b.xml"), addFeed("/c.xml"))

        val report = repo.refreshAll()

        assertThat(report.outcomes[sick]).isInstanceOf(FeedRefreshOutcome.Failed::class.java)
        healthy.forEach {
            assertThat(report.outcomes[it]).isEqualTo(FeedRefreshOutcome.Updated(newEntries = 1))
        }
        assertThat(entries.countAll()).isEqualTo(3)
        assertThat(report.newEntries).isEqualTo(3)
        assertThat(report.failed).isEqualTo(1)
    }

    @Test
    fun `a failure is recorded against the source and cleared by the next success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(ok(rss(item("a1"))))
        val id = addFeed()

        repo.refresh(id)
        repo.refresh(id)

        val sick = feeds.findById(id)!!
        assertThat(sick.lastError).contains("500")
        assertThat(sick.consecutiveFailures).isEqualTo(2)
        assertThat(sick.lastSuccessAt).isNull()
        assertThat(sick.lastFetchedAt).isEqualTo(now)

        repo.refresh(id)

        val recovered = feeds.findById(id)!!
        assertThat(recovered.lastError).isNull()
        assertThat(recovered.consecutiveFailures).isEqualTo(0)
    }

    @Test
    fun `a web page where a feed should be is a per-source failure, not a crash`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("<!doctype html><html><body>Not a feed</body></html>")
                .addHeader("Content-Type", "text/html"),
        )
        val id = addFeed()

        val outcome = repo.refresh(id)

        assertThat(outcome).isInstanceOf(FeedRefreshOutcome.Failed::class.java)
        assertThat((outcome as FeedRefreshOutcome.Failed).message).contains("web page")
        assertThat(entries.countAll()).isEqualTo(0)
    }

    @Test
    fun `at most four feeds are in flight at once`() = runTest {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                Thread.sleep(120)
                inFlight.decrementAndGet()
                return ok(rss(item("a1")))
            }
        }
        repeat(8) { addFeed("/feed$it.xml") }

        repo.refreshAll()

        assertThat(peak.get()).isAtMost(4)
        assertThat(peak.get()).isGreaterThan(1)
    }

    // ---- retention -------------------------------------------------------------

    @Test
    fun `read entries past the retention window are dropped unless still in the feed`() =
        runTest {
            val old = now - java.time.Duration.ofDays(40).toMillis()
            server.enqueue(ok(rss(item("kept", date = null))))
            val id = addFeed()
            entries.insert(
                storedEntry(id, guid = "kept", publishedAt = old, isRead = true, fetchedAt = old),
            )
            entries.insert(
                storedEntry(id, guid = "gone", publishedAt = old, isRead = true, fetchedAt = old),
            )
            entries.insert(
                storedEntry(id, guid = "unread", publishedAt = old, isRead = false, fetchedAt = old),
            )

            repo.refresh(id)

            assertThat(entries.findByGuid(id, "kept")).isNotNull()
            assertThat(entries.findByGuid(id, "unread")).isNotNull()
            assertThat(entries.findByGuid(id, "gone")).isNull()
        }

    @Test
    fun `a failed refresh prunes nothing`() = runTest {
        val old = now - java.time.Duration.ofDays(40).toMillis()
        server.enqueue(MockResponse().setResponseCode(500))
        val id = addFeed()
        entries.insert(
            storedEntry(id, guid = "gone", publishedAt = old, isRead = true, fetchedAt = old),
        )

        repo.refresh(id)

        assertThat(entries.findByGuid(id, "gone")).isNotNull()
    }

    // ---- helpers ---------------------------------------------------------------

    private suspend fun addFeed(path: String = "/feed.xml"): Long = feeds.insert(
        FeedEntity(
            feedUrl = server.url(path).toString(),
            siteUrl = null,
            title = path,
            customTitle = null,
            faviconUrl = null,
            etag = null,
            lastModified = null,
            lastFetchedAt = null,
            lastSuccessAt = null,
            lastError = null,
            addedAt = now,
        ),
    )

    private fun storedEntry(
        feedId: Long,
        guid: String,
        publishedAt: Long,
        isRead: Boolean,
        fetchedAt: Long,
    ) = dev.mkiros.perch.data.db.entity.EntryEntity(
        feedId = feedId,
        guid = guid,
        title = guid,
        link = null,
        author = null,
        publishedAt = publishedAt,
        publishedIsEstimated = false,
        summary = null,
        contentHtml = null,
        imageUrl = null,
        isRead = isRead,
        readAt = if (isRead) publishedAt else null,
        fetchedAt = fetchedAt,
    )

    private fun ok(body: String) = MockResponse()
        .setBody(body)
        .addHeader("Content-Type", "application/rss+xml; charset=utf-8")

    /** Serves [overrides] by path and a one-item feed for everything else. */
    private fun pathDispatcher(vararg overrides: Pair<String, MockResponse>) =
        object : Dispatcher() {
            private val byPath = overrides.toMap()
            override fun dispatch(request: RecordedRequest): MockResponse =
                byPath[request.path] ?: ok(rss(item("a1")))
        }

    private fun rss(vararg items: String, channelDate: String? = "Mon, 03 Aug 2026 09:00:00 GMT") =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <rss version="2.0"><channel>
          <title>Example Feed</title>
          <link>https://example.com/</link>
          ${channelDate?.let { "<lastBuildDate>$it</lastBuildDate>" } ?: ""}
          ${items.joinToString("\n")}
        </channel></rss>
        """.trimIndent()

    private fun item(
        guid: String,
        title: String = guid,
        date: String? = "Mon, 03 Aug 2026 10:00:00 GMT",
        content: String? = null,
    ) = buildString {
        append("<item>")
        append("<guid isPermaLink=\"false\">$guid</guid>")
        append("<title>$title</title>")
        append("<link>https://example.com/$guid</link>")
        date?.let { append("<pubDate>$it</pubDate>") }
        content?.let { append("<content:encoded xmlns:content=\"http://purl.org/rss/1.0/modules/content/\"><![CDATA[$it]]></content:encoded>") }
        append("</item>")
    }

    private fun corpusSnapshot(name: String): String {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            val file = File(dir, "fixtures/snapshots/$name")
            if (file.isFile) return file.readText()
            dir = dir.parentFile
        }
        error("fixtures/snapshots/$name not found")
    }
}
