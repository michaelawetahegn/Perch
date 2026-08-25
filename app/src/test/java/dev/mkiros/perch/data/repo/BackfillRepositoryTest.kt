package dev.mkiros.perch.data.repo

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.EntryDao
import dev.mkiros.perch.data.db.FeedDao
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.parse.FetchedPage
import dev.mkiros.perch.data.parse.PageFetcher
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Z02 — filling a source's history in behind its feed (PLAN-7 §0.3/§0.4, issue #21).
 *
 * Every clause of §0.3 is its own test: bounded, serialised, polite (a delay between
 * fetches, `robots.txt` obeyed), interruptible, resumable, idempotent, and one bad page
 * never abandons the rest. [FakeFetcher] stands in for the network throughout — this suite
 * never touches it.
 */
@RunWith(RobolectricTestRunner::class)
class BackfillRepositoryTest {

    private lateinit var db: PerchDatabase
    private lateinit var feeds: FeedDao
    private lateinit var entries: EntryDao
    private lateinit var fetcher: FakeFetcher
    private lateinit var delays: MutableList<Long>

    private val now = Instant.parse("2026-08-24T12:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        db = PerchDatabase.inMemory(ApplicationProvider.getApplicationContext())
        feeds = db.feedDao()
        entries = db.entryDao()
        fetcher = FakeFetcher()
        delays = mutableListOf()
    }

    @After
    fun tearDown() = db.close()

    private fun repo() = BackfillRepository(
        feedDao = feeds,
        entryDao = entries,
        fetcher = fetcher,
        clock = fixedClock(),
        delay = { delays += it },
    )

    @Test
    fun `plan finds posts not yet stored and is worthwhile once the archive at least doubles the feed`() = runTest {
        val feedId = addFeed(entryCount = 1, oldest = Instant.parse("2026-08-01T00:00:00Z"))
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(POST_1, POST_2, POST_3)

        val plan = repo().plan(feedId)!!

        assertThat(plan.newPostCount).isEqualTo(3)
        assertThat(plan.isWorthwhile).isTrue()
    }

    @Test
    fun `plan is not worthwhile for a handful of extra posts`() = runTest {
        val feedId = addFeed(entryCount = 10, oldest = Instant.parse("2026-08-01T00:00:00Z"))
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(POST_1)

        val plan = repo().plan(feedId)!!

        assertThat(plan.isWorthwhile).isFalse()
    }

    @Test
    fun `plan drops a candidate already stored under the feed`() = runTest {
        val feedId = addFeed(entryCount = 0, oldest = null)
        storeExisting(feedId, POST_1)
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(POST_1, POST_2)

        val plan = repo().plan(feedId)!!

        assertThat(plan.toFetch.map { it.url }).containsExactly(POST_2)
    }

    @Test
    fun `plan bounds toFetch at MAX_PAGES but reports the uncapped count`() = runTest {
        val feedId = addFeed(entryCount = 0, oldest = null)
        val many = (1..(BackfillRepository.MAX_PAGES + 5)).map { "https://example.com/2020/01/$it/post-$it" }
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(*many.toTypedArray())

        val plan = repo().plan(feedId)!!

        assertThat(plan.newPostCount).isEqualTo(many.size)
        assertThat(plan.toFetch).hasSize(BackfillRepository.MAX_PAGES)
    }

    @Test
    fun `run fetches and stores every planned post under the real feed's id, unread and unsaved`() = runTest {
        val feedId = addFeed(entryCount = 0, oldest = null, siteUrl = SITE.trimEnd('/'))
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(POST_1, POST_2)
        fetcher.pages[POST_1] = article("First Post", "2020-01-01T00:00:00Z")
        fetcher.pages[POST_2] = article("Second Post", "2020-02-02T00:00:00Z")

        val result = repo().run(feedId)

        assertThat(result.stored).isEqualTo(2)
        assertThat(entries.countAll()).isEqualTo(2)
        val stored = entries.guidsForFeed(feedId)
        assertThat(stored).containsExactly(POST_1, POST_2)
        val row = entries.findByGuid(feedId, POST_1)!!
        assertThat(row.isRead).isFalse()
        assertThat(row.isSaved).isFalse()
        assertThat(row.title).isEqualTo("First Post")
    }

    @Test
    fun `run pauses between fetches and never runs them in parallel`() = runTest {
        val feedId = addFeed(entryCount = 0, oldest = null, siteUrl = SITE.trimEnd('/'))
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(POST_1, POST_2, POST_3)
        fetcher.pages[POST_1] = article("A", "2020-01-01T00:00:00Z")
        fetcher.pages[POST_2] = article("B", "2020-01-02T00:00:00Z")
        fetcher.pages[POST_3] = article("C", "2020-01-03T00:00:00Z")

        repo().run(feedId)

        // Three pages, two gaps between them — no delay before the very first fetch.
        assertThat(delays).hasSize(2)
        assertThat(delays).containsExactly(BackfillRepository.DEFAULT_DELAY_MILLIS, BackfillRepository.DEFAULT_DELAY_MILLIS)
    }

    @Test
    fun `run skips a URL robots-txt disallows`() = runTest {
        val feedId = addFeed(entryCount = 0, oldest = null, siteUrl = SITE.trimEnd('/'))
        fetcher.pages[SITE + "robots.txt"] = text("User-agent: *\nDisallow: /2020/01/\n")
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(POST_1, POST_2)
        fetcher.pages[POST_1] = article("Blocked", "2020-01-01T00:00:00Z") // under /2020/01/
        fetcher.pages[POST_2] = article("Allowed", "2020-02-02T00:00:00Z")

        val result = repo().run(feedId)

        assertThat(result.skippedByRobots).isEqualTo(1)
        assertThat(result.stored).isEqualTo(1)
        assertThat(entries.guidsForFeed(feedId)).containsExactly(POST_2)
        assertThat(fetcher.requested).doesNotContain(POST_1)
    }

    @Test
    fun `one page failing to fetch does not abandon the rest`() = runTest {
        val feedId = addFeed(entryCount = 0, oldest = null, siteUrl = SITE.trimEnd('/'))
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(POST_1, POST_2)
        // POST_1 is discovered but never answers — fetcher.fetch returns null for it.
        fetcher.pages[POST_2] = article("Second", "2020-02-02T00:00:00Z")

        val result = repo().run(feedId)

        assertThat(result.failed).isEqualTo(1)
        assertThat(result.stored).isEqualTo(1)
        assertThat(entries.guidsForFeed(feedId)).containsExactly(POST_2)
    }

    @Test
    fun `a second run over the same site stores nothing new`() = runTest {
        val feedId = addFeed(entryCount = 0, oldest = null, siteUrl = SITE.trimEnd('/'))
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(POST_1, POST_2)
        fetcher.pages[POST_1] = article("First", "2020-01-01T00:00:00Z")
        fetcher.pages[POST_2] = article("Second", "2020-02-02T00:00:00Z")
        repo().run(feedId)

        val second = repo().run(feedId)

        assertThat(second.attempted).isEqualTo(0)
        assertThat(second.stored).isEqualTo(0)
        assertThat(entries.countAll()).isEqualTo(2)
    }

    @Test
    fun `a cancelled run leaves what it had already stored, and a later run finishes the rest`() = runTest {
        val feedId = addFeed(entryCount = 0, oldest = null, siteUrl = SITE.trimEnd('/'))
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(POST_1, POST_2)
        fetcher.pages[POST_1] = article("First", "2020-01-01T00:00:00Z")
        fetcher.pages[POST_2] = article("Second", "2020-02-02T00:00:00Z")

        val cancelAfterFirst = repo().run(feedId, isCancelled = { entries.countAll() >= 1 })
        assertThat(cancelAfterFirst.stored).isEqualTo(1)
        assertThat(entries.countAll()).isEqualTo(1)

        val resumed = repo().run(feedId)
        assertThat(resumed.stored).isEqualTo(1)
        assertThat(entries.countAll()).isEqualTo(2)
    }

    @Test
    fun `the page cap stops discovery from fetching an unbounded archive`() = runTest {
        val feedId = addFeed(entryCount = 0, oldest = null, siteUrl = SITE.trimEnd('/'))
        val many = (1..(BackfillRepository.MAX_PAGES + 5)).map { "https://example.com/2020/01/$it/post-$it" }
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(*many.toTypedArray())
        many.forEach { fetcher.pages[it] = article("Post", "2020-01-01T00:00:00Z") }

        val result = repo().run(feedId)

        assertThat(result.attempted).isEqualTo(BackfillRepository.MAX_PAGES)
        assertThat(entries.countAll()).isEqualTo(BackfillRepository.MAX_PAGES)
    }

    // ---- §0.3a: the date chain ---------------------------------------------------

    @Test
    fun `a backfilled post uses the page's own metadata date when it has one`() = runTest {
        val feedId = addFeed(entryCount = 0, oldest = null, siteUrl = SITE.trimEnd('/'))
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(POST_1, lastmod = "2019-01-01T00:00:00Z")
        fetcher.pages[POST_1] = article("Dated", "2020-05-05T00:00:00Z")

        repo().run(feedId)

        val row = entries.findByGuid(feedId, POST_1)!!
        assertThat(row.publishedAt).isEqualTo(Instant.parse("2020-05-05T00:00:00Z").toEpochMilli())
        assertThat(row.publishedIsEstimated).isFalse()
    }

    // These two use RFC 5005 discovery rather than a sitemap: its posts carry no dated-URL
    // requirement (`ArchiveDiscovery.isLikelyPost` demands one for a sitemap URL), so it is
    // the shape that actually exercises PageMetadata's URL-date rung declining.

    @Test
    fun `a backfilled post with no page date falls back to the archived feed's own date`() = runTest {
        val feedId = addFeed(entryCount = 0, oldest = null, siteUrl = SITE.trimEnd('/'))
        fetcher.pages[SITE + "feed.xml"] = feedWithArchive()
        fetcher.pages[SITE + "archive"] = archivePage(UNDATED_POST, updated = "2019-03-03T00:00:00Z")
        fetcher.pages[UNDATED_POST] = article("Undated", published = null)

        repo().run(feedId)

        val row = entries.findByGuid(feedId, UNDATED_POST)!!
        assertThat(row.publishedAt).isEqualTo(Instant.parse("2019-03-03T00:00:00Z").toEpochMilli())
        assertThat(row.publishedIsEstimated).isFalse()
    }

    @Test
    fun `a backfilled post with no date anywhere never sorts above one published today`() = runTest {
        val feedId = addFeed(entryCount = 0, oldest = null, siteUrl = SITE.trimEnd('/'))
        fetcher.pages[SITE + "feed.xml"] = feedWithArchive()
        fetcher.pages[SITE + "archive"] = archivePage(UNDATED_POST, updated = null)
        fetcher.pages[UNDATED_POST] = article("Mystery", published = null)
        val todayEntryId = entries.insert(
            EntryEntity(
                feedId = feedId, guid = "today", title = "Fresh", link = null, author = null,
                publishedAt = now, publishedIsEstimated = false, summary = null, contentHtml = null,
                imageUrl = null, readAt = null, fetchedAt = now,
            ),
        )

        repo().run(feedId)

        val backfilled = entries.findByGuid(feedId, UNDATED_POST)!!
        val today = entries.findById(todayEntryId)!!
        assertThat(backfilled.publishedIsEstimated).isTrue()
        assertThat(backfilled.publishedAt).isLessThan(today.publishedAt)
    }

    // ---- reach (§0.4) --------------------------------------------------------------

    @Test
    fun `a feed's reach is the oldest and count of what is actually stored`() = runTest {
        val feedId = addFeed(entryCount = 0, oldest = null)
        storeExisting(feedId, "guid-1", publishedAt = Instant.parse("2026-06-01T00:00:00Z"))
        storeExisting(feedId, "guid-2", publishedAt = Instant.parse("2026-07-01T00:00:00Z"))

        val reach = entries.reach(feedId)

        assertThat(reach.entryCount).isEqualTo(2)
        assertThat(reach.oldestPublishedAt).isEqualTo(Instant.parse("2026-06-01T00:00:00Z").toEpochMilli())
    }

    // ---- harness --------------------------------------------------------------------

    private fun fixedClock() = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)

    private suspend fun addFeed(entryCount: Int, oldest: Instant?, siteUrl: String = SITE.trimEnd('/')): Long {
        val feedId = feeds.insert(
            FeedEntity(
                feedUrl = SITE + "feed.xml",
                siteUrl = siteUrl,
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
        repeat(entryCount) { i ->
            storeExisting(feedId, "existing-$i", publishedAt = oldest ?: Instant.ofEpochMilli(now))
        }
        return feedId
    }

    private suspend fun storeExisting(feedId: Long, guid: String, publishedAt: Instant = Instant.ofEpochMilli(now)) {
        entries.insert(
            EntryEntity(
                feedId = feedId, guid = guid, title = guid, link = guid, author = null,
                publishedAt = publishedAt.toEpochMilli(), publishedIsEstimated = false, summary = null,
                contentHtml = null, imageUrl = null, readAt = null, fetchedAt = now,
            ),
        )
    }

    private class FakeFetcher(val pages: MutableMap<String, FetchedPage> = mutableMapOf()) : PageFetcher {
        val requested = mutableListOf<String>()
        override suspend fun fetch(url: String): FetchedPage? {
            requested += url
            val page = pages[url] ?: return null
            // Fixtures below don't know their own URL yet when they're built (they're
            // stored by URL as the map key) — stamp it on here so guid = final URL holds.
            return if (page.finalUrl.isEmpty()) FetchedPage(page.bytes, page.contentType, url) else page
        }
    }

    private companion object {
        const val SITE = "https://example.com/"
        const val POST_1 = "https://example.com/2020/01/01/post-one"
        const val POST_2 = "https://example.com/2020/02/02/post-two"
        const val POST_3 = "https://example.com/2020/03/03/post-three"

        /** No `/YYYY/MM/DD/` segment — the one URL shape PageMetadata's weakest date rung declines. */
        const val UNDATED_POST = "https://example.com/posts/undated-post"

        fun text(body: String) = FetchedPage(body.toByteArray(), "text/plain", "")

        /** The feed half of an RFC 5005 fixture: `rel="prev-archive"` to `SITE + "archive"`. */
        fun feedWithArchive() = FetchedPage(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom" xmlns:fh="http://purl.org/syndication/history/1.0">
              <title>A blog</title>
              <link rel="prev-archive" href="${SITE}archive"/>
            </feed>
            """.trimIndent().toByteArray(),
            "application/atom+xml",
            SITE + "feed.xml",
        )

        /** The archive half: one entry, [entryUrl], with whatever date it declares (or none). */
        fun archivePage(entryUrl: String, updated: String?) = FetchedPage(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom" xmlns:fh="http://purl.org/syndication/history/1.0">
              <title>A blog — archive</title>
              <entry>
                <title>Archived</title>
                <id>$entryUrl</id>
                <link href="$entryUrl"/>
                ${updated?.let { "<updated>$it</updated>" }.orEmpty()}
              </entry>
            </feed>
            """.trimIndent().toByteArray(),
            "application/atom+xml",
            SITE + "archive",
        )

        fun sitemapOf(vararg urls: String, lastmod: String? = null) = FetchedPage(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              ${urls.joinToString("\n") { u -> "<url><loc>$u</loc>${lastmod?.let { "<lastmod>$it</lastmod>" }.orEmpty()}</url>" }}
            </urlset>
            """.trimIndent().toByteArray(),
            "application/xml",
            SITE,
        )

        fun article(title: String, published: String?) = FetchedPage(
            """
            <html><head>
              <meta property="og:title" content="$title">
              ${published?.let { """<meta property="article:published_time" content="$it">""" }.orEmpty()}
            </head><body><article><p>${"Real prose, with commas and length. ".repeat(30)}</p></article></body></html>
            """.trimIndent().toByteArray(),
            "text/html",
            "",
        )
    }
}
