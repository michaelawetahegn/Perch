package dev.mkiros.perch.data.repo

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.EntryDao
import dev.mkiros.perch.data.db.FeedDao
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.extract.ArticleFixtures
import dev.mkiros.perch.data.parse.FetchedPage
import dev.mkiros.perch.data.parse.PageFetcher
import dev.mkiros.perch.support.testEntry
import dev.mkiros.perch.support.testFeed
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.jsoup.Jsoup
import org.robolectric.RobolectricTestRunner

/**
 * U10's write path: fetch the entry's own page, extract the article, and put it where the
 * feed body would have been — with the one rule that makes the feature safe to run
 * automatically, which is that it never leaves the reader with less than they had.
 *
 * Every page comes from `fixtures/articles/`, so nothing here touches the network.
 */
@RunWith(RobolectricTestRunner::class)
class ArticleTextRepositoryTest {

    private lateinit var db: PerchDatabase
    private lateinit var entries: EntryDao
    private lateinit var feeds: FeedDao
    private var feedId: Long = 0

    private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_500_000L), ZoneOffset.UTC)

    @Before
    fun setUp() = runTest {
        db = PerchDatabase.inMemory(ApplicationProvider.getApplicationContext())
        entries = db.entryDao()
        feeds = db.feedDao()
        feedId = feeds.insert(feed())
    }

    @After
    fun tearDown() = db.close()

    /** §0's first shape: fabiensanglard.net ships no body at all. */
    @Test
    fun `an entry with no body gets the article from its own page`() = runTest {
        val fixture = ArticleFixtures.noBody.first { it.slug == "fabiensanglard-tb4" }
        val id = entries.insert(entry(link = fixture.url, contentHtml = null))
        val repository = repositoryFor(fixture.slug)

        val recovered = repository.loadFullText(id)

        assertThat(recovered).isNotNull()
        val stored = entries.findById(id)!!
        assertThat(text(stored.contentHtml)).contains(fixture.last)
        assertThat(stored.fullTextAt).isEqualTo(1_700_000_500_000L)
    }

    /** §0's second shape: an excerpt standing in for the body. */
    @Test
    fun `an excerpt-only entry ends up with far more than the excerpt`() = runTest {
        val fixture = ArticleFixtures.excerptOnly.first { it.slug == "gpuopen-adaptive-subdivision" }
        val excerpt = "<p>Learn how fast, crack-free GPU work graph subdivision works.</p>"
        val id = entries.insert(entry(link = fixture.url, contentHtml = excerpt, bodyIsExcerpt = true))

        repositoryFor(fixture.slug).loadFullText(id)

        val stored = entries.findById(id)!!
        assertThat(text(stored.contentHtml).length).isGreaterThan(10 * text(excerpt).length)
        assertThat(text(stored.contentHtml)).contains(fixture.mid)
    }

    /**
     * The rule that lets this run without asking. An extractor is a heuristic and will be
     * wrong sometimes; being wrong must cost the reader nothing.
     */
    @Test
    fun `an extraction thinner than the feed body is discarded and the body kept`() = runTest {
        val long = "<p>${"The feed already shipped the whole article. ".repeat(80)}</p>"
        val id = entries.insert(entry(link = "https://example.com/a", contentHtml = long))
        val repository = repositoryServing(page = "<html><body><article><p>A stub.</p></article></body></html>")

        val recovered = repository.loadFullText(id)

        assertThat(recovered).isNull()
        val stored = entries.findById(id)!!
        assertThat(stored.contentHtml).isEqualTo(long)
        assertThat(stored.fullTextAt).isNull()
    }

    @Test
    fun `a page that cannot be fetched leaves the entry exactly as it was`() = runTest {
        val id = entries.insert(entry(link = "https://example.com/a", contentHtml = "<p>Stub.</p>"))
        val repository = ArticleTextRepository(entries, { null }, clock)

        assertThat(repository.loadFullText(id)).isNull()
        assertThat(entries.findById(id)!!.contentHtml).isEqualTo("<p>Stub.</p>")
        assertThat(entries.findById(id)!!.fullTextAt).isNull()
    }

    @Test
    fun `an entry with no link cannot be extracted and is not fetched`() = runTest {
        val id = entries.insert(entry(link = null, contentHtml = null))
        var fetched = false
        val repository = ArticleTextRepository(entries, { fetched = true; null }, clock)

        assertThat(repository.loadFullText(id)).isNull()
        assertThat(fetched).isFalse()
    }

    /**
     * §0 puts `og:image` last in the thumbnail chain and allows it *only* when the page is
     * already being fetched for its text — which is here. An entry that already has a
     * thumbnail keeps it; the feed knows its own images better than a social-card tag does.
     */
    @Test
    fun `a thumbnail-less entry picks up the page's og image while it is open`() = runTest {
        val page = """
            <html><head><meta property="og:image" content="/img/card.png"></head>
            <body><article><p>${"Real prose with commas, and length. ".repeat(30)}</p></article></body></html>
        """.trimIndent()
        val id = entries.insert(entry(link = "https://example.com/post/", contentHtml = null))

        repositoryServing(page = page, url = "https://example.com/post/").loadFullText(id)

        assertThat(entries.findById(id)!!.imageUrl).isEqualTo("https://example.com/img/card.png")
    }

    /**
     * The two questions the fetch answers are independent (U15 gate 4). A feed that ships
     * the whole article but no picture is the commonest shape there is, and the page it
     * links to almost always carries a social card — so a body argument the page loses must
     * not take the thumbnail down with it. The body stays exactly as the feed wrote it.
     */
    @Test
    fun `a page whose article loses to the feed body still hands over its og image`() = runTest {
        val body = "<p>${"The feed already shipped the whole article. ".repeat(80)}</p>"
        val page = """
            <html><head><meta property="og:image" content="/img/card.png"></head>
            <body><article><p>A stub.</p></article></body></html>
        """.trimIndent()
        val id = entries.insert(entry(link = "https://example.com/post/", contentHtml = body))

        val recovered = repositoryServing(page = page, url = "https://example.com/post/")
            .loadFullText(id)

        assertThat(recovered).isNull()
        val stored = entries.findById(id)!!
        assertThat(stored.imageUrl).isEqualTo("https://example.com/img/card.png")
        assertThat(stored.contentHtml).isEqualTo(body)
        assertThat(stored.fullTextAt).isNull()
    }

    /**
     * §0's chain is `media:*` → `enclosure` → **the first real `<img>` in the body** →
     * `og:image`, and the body it means is whichever one the reader ends up with. Once an
     * extraction has replaced the feed's body, the recovered article's own lead picture is
     * that rung — a diagram the author drew for the piece, rather than the site-wide social
     * card a `og:image` so often is. Only when the recovered body has no picture at all
     * does the card get its turn.
     */
    @Test
    fun `a recovered body's own first image outranks the page's social card`() = runTest {
        val page = """
            <html><head><meta property="og:image" content="/img/site-card.png"></head>
            <body><article>
              <p>${"Real prose with commas, and length. ".repeat(30)}</p>
              <img src="/img/diagram.png" width="800" height="600">
            </article></body></html>
        """.trimIndent()
        val id = entries.insert(entry(link = "https://example.com/post/", contentHtml = null))

        repositoryServing(page = page, url = "https://example.com/post/").loadFullText(id)

        assertThat(entries.findById(id)!!.imageUrl).isEqualTo("https://example.com/img/diagram.png")
    }

    @Test
    fun `an entry that already has a thumbnail keeps the one the feed gave it`() = runTest {
        val page = """
            <html><head><meta property="og:image" content="https://example.com/img/card.png"></head>
            <body><article><p>${"Real prose with commas, and length. ".repeat(30)}</p></article></body></html>
        """.trimIndent()
        val id = entries.insert(
            entry(link = "https://example.com/post/", contentHtml = null, imageUrl = "https://example.com/feed.png"),
        )

        repositoryServing(page = page, url = "https://example.com/post/").loadFullText(id)

        assertThat(entries.findById(id)!!.imageUrl).isEqualTo("https://example.com/feed.png")
    }

    /**
     * A refresh must not undo the fetch. `upsertAll` overwrites `contentHtml` from the feed
     * on every single poll, so without this the extracted article survives exactly until
     * the next time the worker runs — the same trap that would have emptied the to-read
     * list in U04.
     */
    @Test
    fun `a refresh does not overwrite the article Perch went and fetched`() = runTest {
        val fixture = ArticleFixtures.noBody.first { it.slug == "fabiensanglard-tb4" }
        val id = entries.insert(entry(guid = "g1", link = fixture.url, contentHtml = null))
        repositoryFor(fixture.slug).loadFullText(id)
        val extracted = entries.findById(id)!!.contentHtml

        entries.upsertAll(listOf(entry(guid = "g1", link = fixture.url, contentHtml = null)))

        val after = entries.findById(id)!!
        assertThat(after.contentHtml).isEqualTo(extracted)
        assertThat(after.fullTextAt).isEqualTo(1_700_000_500_000L)
    }

    /** If the feed later ships something longer, the feed wins — it is the source. */
    @Test
    fun `a feed that starts shipping a longer body replaces the extraction`() = runTest {
        val id = entries.insert(entry(guid = "g1", link = "https://example.com/a", contentHtml = null))
        val page = "<html><body><article><p>${"Extracted prose, with commas. ".repeat(20)}</p></article></body></html>"
        repositoryServing(page = page).loadFullText(id)

        val fuller = "<p>${"The feed caught up and shipped everything, at last. ".repeat(80)}</p>"
        entries.upsertAll(listOf(entry(guid = "g1", link = "https://example.com/a", contentHtml = fuller)))

        val after = entries.findById(id)!!
        assertThat(after.contentHtml).isEqualTo(fuller)
        assertThat(after.fullTextAt).isNull()
    }

    /**
     * D03/#36. The article screen starts the fetch when the reader opens the article, and
     * the reader can tap Read later or Like while it is still in flight — seconds, on a
     * slow page. Writing the whole row back afterwards puts the flags as they were when
     * the fetch *started* on top of the reader's tap, and the icon visibly flips off
     * again. Only the three columns this fetch actually owns may be written.
     */
    @Test
    fun `a Read later tapped while the page is loading survives the write-back`() = runTest {
        val id = entries.insert(entry(link = "https://example.com/post/", contentHtml = null))
        val page = "<html><body><article><p>${"Real prose with commas, and length. ".repeat(30)}</p></article></body></html>"
        val repository = ArticleTextRepository(
            entryDao = entries,
            fetcher = { requested ->
                entries.setSaved(id, true, 1_700_000_400_000L)
                entries.setStarred(id, true, 1_700_000_400_000L)
                FetchedPage(page.toByteArray(), "text/html", requested)
            },
            clock = clock,
        )

        val recovered = repository.loadFullText(id)

        val stored = entries.findById(id)!!
        assertThat(stored.isSaved).isTrue()
        assertThat(stored.isStarred).isTrue()
        assertThat(text(stored.contentHtml)).contains("Real prose with commas")
        // What the screen re-renders from, so it has to carry the reader's tap too.
        assertThat(recovered!!.isSaved).isTrue()
    }

    /**
     * The same hazard, from the other side: the refresh worker can land mid-fetch and
     * correct the title the feed shipped. The write-back must not roll that back either.
     */
    @Test
    fun `a title a refresh corrected during the fetch is not reverted`() = runTest {
        val id = entries.insert(entry(guid = "g1", link = "https://example.com/post/", contentHtml = null))
        val page = "<html><body><article><p>${"Real prose with commas, and length. ".repeat(30)}</p></article></body></html>"
        val repository = ArticleTextRepository(
            entryDao = entries,
            fetcher = { requested ->
                entries.upsertAll(
                    listOf(
                        entry(guid = "g1", link = "https://example.com/post/", contentHtml = null)
                            .copy(title = "The corrected title"),
                    ),
                )
                FetchedPage(page.toByteArray(), "text/html", requested)
            },
            clock = clock,
        )

        repository.loadFullText(id)

        assertThat(entries.findById(id)!!.title).isEqualTo("The corrected title")
    }

    // ---- fixtures ---------------------------------------------------------------

    private fun text(html: String?): String = Jsoup.parse(html.orEmpty()).text()

    /** A fetcher that answers every request with one harvested page. */
    private fun repositoryFor(slug: String): ArticleTextRepository {
        val bytes = File(ArticleFixtures.dir(), "$slug.html").readBytes()
        return ArticleTextRepository(
            entryDao = entries,
            fetcher = { url -> FetchedPage(bytes, "text/html; charset=utf-8", url) },
            clock = clock,
        )
    }

    private fun repositoryServing(page: String, url: String? = null): ArticleTextRepository =
        ArticleTextRepository(
            entryDao = entries,
            fetcher = { requested ->
                FetchedPage(page.toByteArray(), "text/html", url ?: requested)
            },
            clock = clock,
        )

    private fun feed() = testFeed(
        feedUrl = "https://example.com/feed",
        siteUrl = "https://example.com/",
        addedAt = 1_700_000_000_000L,
    )

    private fun entry(
        guid: String = "guid",
        link: String?,
        contentHtml: String?,
        bodyIsExcerpt: Boolean = false,
        imageUrl: String? = null,
    ) = testEntry(
        feedId = feedId,
        guid = guid,
        title = "An entry",
        link = link,
        publishedAt = 1_700_000_000_000L,
        contentHtml = contentHtml,
        imageUrl = imageUrl,
        bodyIsExcerpt = bodyIsExcerpt,
    )
}
