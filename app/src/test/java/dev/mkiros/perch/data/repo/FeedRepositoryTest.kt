package dev.mkiros.perch.data.repo

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
        db = PerchDatabase.inMemory(ApplicationProvider.getApplicationContext())
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
            val readId = entries.unreadIds(id, folderId = null).first()
            entries.setRead(listOf(readId), isRead = true, readAt = now)

            val second = repo.refresh(id)

            assertThat(second).isEqualTo(FeedRefreshOutcome.Updated(newEntries = 0))
            assertThat(entries.countAll()).isEqualTo(afterFirst)
            assertThat(entries.findById(readId)!!.isRead).isTrue()
        }

    /**
     * U04's contract, asserted end to end rather than at the DAO: a real feed is fetched
     * and parsed twice, and everything the reader did to an entry between the two runs
     * survives the second one.
     *
     * A parsed entry carries `isSaved = false`, `savedAt = null`, `starredAt = null` every
     * single time, so a refresh that wrote a parsed row through verbatim would quietly
     * empty the to-read list — the one failure mode a user would never think to report as
     * a bug, because the list would simply look like they had never saved anything.
     */
    @Test
    fun `a fresh parse of the same feed keeps saved, liked and unread state`() = runTest {
        val snapshot = corpusSnapshot("ciechanow-ski.xml")
        server.enqueue(ok(snapshot))
        server.enqueue(ok(snapshot))
        val id = addFeed()
        repo.refresh(id)
        val entryId = entries.unreadIds(id, folderId = null).first()
        val stored = entries.findById(entryId)!!
        entries.update(
            stored.copy(
                isSaved = true,
                savedAt = 111L,
                isStarred = true,
                starredAt = 222L,
                isRead = true,
                readAt = 333L,
            ),
        )
        entries.setRead(listOf(entryId), isRead = false, readAt = null)

        repo.refresh(id)

        val after = entries.findById(entryId)!!
        assertThat(after.isSaved).isTrue()
        assertThat(after.savedAt).isEqualTo(111L)
        assertThat(after.isStarred).isTrue()
        assertThat(after.starredAt).isEqualTo(222L)
        assertThat(after.isRead).isFalse()
        assertThat(after.readAt).isNull()
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

    /**
     * Retention bounds storage; it does not overrule the reader. A saved article swept out
     * from under the to-read list thirty days later is the same silent emptying U04 exists
     * to prevent, only slower — and *Liked* is documented as permanent.
     */
    @Test
    fun `retention never sweeps an entry the reader saved or liked`() = runTest {
        val old = now - java.time.Duration.ofDays(40).toMillis()
        server.enqueue(ok(rss(item("a1"))))
        val id = addFeed()
        entries.insert(
            storedEntry(id, "saved", publishedAt = old, isRead = true, fetchedAt = old, isSaved = true),
        )
        entries.insert(
            storedEntry(id, "liked", publishedAt = old, isRead = true, fetchedAt = old, isStarred = true),
        )
        entries.insert(storedEntry(id, "gone", publishedAt = old, isRead = true, fetchedAt = old))

        repo.refresh(id)

        assertThat(entries.findByGuid(id, "saved")).isNotNull()
        assertThat(entries.findByGuid(id, "liked")).isNotNull()
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

    // ---- adding, removing and renaming sources ---------------------------------

    @Test
    fun `a pasted feed address resolves without committing, then commits with its entries`() =
        runTest {
            server.enqueue(ok(rss(item("a1"), item("a2"))))

            val resolution = repo.resolve(server.url("/feed.xml").toString())

            assertThat(resolution).isInstanceOf(SourceResolution.Resolved::class.java)
            val resolved = resolution as SourceResolution.Resolved
            assertThat(resolved.title).isEqualTo("Example Feed")
            assertThat(resolved.entryCount).isEqualTo(2)
            // The sheet confirms the resolution before committing it (DESIGN.md §5), so
            // resolving alone must leave the database exactly as it found it.
            assertThat(feeds.countAll()).isEqualTo(0)
            assertThat(entries.countAll()).isEqualTo(0)

            val id = repo.add(resolved)

            val feed = feeds.findById(id)!!
            assertThat(feed.feedUrl).isEqualTo(server.url("/feed.xml").toString())
            assertThat(feed.title).isEqualTo("Example Feed")
            assertThat(feed.customTitle).isNull()
            assertThat(feed.siteUrl).isEqualTo("https://example.com/")
            assertThat(feed.lastSuccessAt).isEqualTo(now)
            assertThat(feed.lastError).isNull()
            // Committing stores the entries already fetched — adding a source costs one
            // round trip, not two.
            assertThat(entries.countAll()).isEqualTo(2)
            assertThat(server.requestCount).isEqualTo(1)
        }

    @Test
    fun `a pasted homepage is resolved through the feed it declares`() = runTest {
        server.dispatcher = pathDispatcher(
            "/" to html("""<link rel="alternate" type="application/rss+xml" href="/feed.xml">"""),
            "/feed.xml" to ok(rss(item("a1"))),
        )

        val id = subscribe("/")

        assertThat(feeds.findById(id)!!.feedUrl).isEqualTo(server.url("/feed.xml").toString())
        assertThat(entries.countAll()).isEqualTo(1)
    }

    @Test
    fun `pasting an address already subscribed is rejected as a duplicate`() = runTest {
        server.enqueue(ok(rss(item("a1"))))
        val existing = subscribe()

        // Nothing further is enqueued: a known address must be recognised before it is
        // fetched, not after.
        val resolution = repo.resolve(server.url("/feed.xml").toString())

        assertThat(resolution).isEqualTo(SourceResolution.AlreadySubscribed(existing, "Example Feed"))
        assertThat(feeds.countAll()).isEqualTo(1)
    }

    @Test
    fun `a homepage whose feed is already subscribed is a duplicate, not a second copy`() =
        runTest {
            server.dispatcher = pathDispatcher(
                "/" to html("""<link rel="alternate" type="application/rss+xml" href="/feed.xml">"""),
                "/feed.xml" to ok(rss(item("a1"))),
            )
            val existing = subscribe("/feed.xml")

            val resolution = repo.resolve(server.url("/").toString())

            assertThat(resolution)
                .isEqualTo(SourceResolution.AlreadySubscribed(existing, "Example Feed"))
            assertThat(feeds.countAll()).isEqualTo(1)
        }

    @Test
    fun `a page that publishes no feed anywhere is rejected without adding anything`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/") html("<p>nothing to subscribe to</p>")
                else MockResponse().setResponseCode(404)
        }
        val url = server.url("/").toString()

        val resolution = repo.resolve(url)

        assertThat(resolution).isEqualTo(SourceResolution.NoFeedFound(url))
        assertThat(feeds.countAll()).isEqualTo(0)
        assertThat(entries.countAll()).isEqualTo(0)
    }

    @Test
    fun `an unreachable address is rejected with the reason, never an exception`() = runTest {
        val dead = server.url("/feed.xml").toString()
        server.shutdown()

        val resolution = repo.resolve(dead)

        assertThat(resolution).isInstanceOf(SourceResolution.Unreachable::class.java)
        assertThat((resolution as SourceResolution.Unreachable).message).isNotEmpty()
        assertThat(feeds.countAll()).isEqualTo(0)
    }

    @Test
    fun `removing a source takes its entries with it`() = runTest {
        server.enqueue(ok(rss(item("a1"), item("a2"))))
        val id = subscribe()
        assertThat(entries.countAll()).isEqualTo(2)

        repo.remove(id)

        assertThat(feeds.findById(id)).isNull()
        assertThat(entries.countAll()).isEqualTo(0)
    }

    @Test
    fun `removing a batch of sources takes those sources and leaves the rest alone`() = runTest {
        server.enqueue(ok(rss(item("a1"), item("a2"))))
        server.enqueue(ok(rss(item("b1"))))
        server.enqueue(ok(rss(item("c1"))))
        val doomed = listOf(subscribe("/a.xml"), subscribe("/b.xml"))
        val kept = subscribe("/c.xml")

        repo.removeAll(doomed)

        assertThat(feeds.getAll().map { it.id }).containsExactly(kept)
        assertThat(entries.countAll()).isEqualTo(1)
    }

    @Test
    fun `renaming a source leaves the title the feed gives itself alone`() = runTest {
        server.enqueue(ok(rss(item("a1"))))
        server.enqueue(ok(rss(item("a1"), item("a2"))))
        val id = subscribe()

        repo.rename(id, "  My Blog  ")
        repo.refresh(id)

        val feed = feeds.findById(id)!!
        assertThat(feed.customTitle).isEqualTo("My Blog")
        assertThat(feed.title).isEqualTo("Example Feed")
    }

    @Test
    fun `renaming to nothing restores the feed's own title`() = runTest {
        server.enqueue(ok(rss(item("a1"))))
        val id = subscribe()
        repo.rename(id, "My Blog")

        repo.rename(id, "   ")

        assertThat(feeds.findById(id)!!.customTitle).isNull()
    }

    // ---- helpers ---------------------------------------------------------------

    /** Paste-to-subscribed, the way the add-source sheet does it: resolve, then commit. */
    private suspend fun subscribe(path: String = "/feed.xml"): Long =
        repo.add(repo.resolve(server.url(path).toString()) as SourceResolution.Resolved)

    private fun html(body: String) = MockResponse()
        .setBody("<!doctype html><html><head>$body</head><body>hello</body></html>")
        .addHeader("Content-Type", "text/html; charset=utf-8")

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
        isSaved: Boolean = false,
        isStarred: Boolean = false,
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
        isSaved = isSaved,
        savedAt = if (isSaved) publishedAt else null,
        isStarred = isStarred,
        starredAt = if (isStarred) publishedAt else null,
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
