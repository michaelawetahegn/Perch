package dev.mkiros.perch.ui.article

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.extract.ArticleFixtures
import dev.mkiros.perch.data.parse.FetchedPage
import dev.mkiros.perch.data.parse.PageFetcher
import dev.mkiros.perch.data.repo.ArticleTextRepository
import dev.mkiros.perch.data.repo.EntryRepository
import dev.mkiros.perch.data.repo.FeedRepository
import dev.mkiros.perch.data.net.FeedFetcher
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.ui.theme.PerchTheme
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * U10 on the reading surface: opening an entry the feed left empty, or filled with a
 * teaser, ends with the article on screen.
 *
 * Every page served here comes from `fixtures/articles/`, so nothing touches the network,
 * and the assertions are about what the *reader* ends up looking at rather than about what
 * the extractor returned — `ArticleExtractorTest` already owns that half.
 *
 * A recovered body is asserted with `assertExists` rather than `assertIsDisplayed`: the
 * article is one scrolling column, so a sentence from the middle or the end of it is
 * composed but far below the fold. Existence means "this came out of the extraction";
 * visibility would only mean "the test window is tall".
 */
@RunWith(RobolectricTestRunner::class)
class ArticleFullTextTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: PerchDatabase
    private lateinit var entries: EntryRepository
    private lateinit var feeds: FeedRepository
    private var feedId: Long = 0

    private val now = Instant.parse("2026-08-08T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    /** How many pages the screen actually asked for — a refresh must never fetch any. */
    private val fetches = AtomicInteger(0)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = PerchDatabase.inMemory(context)
        entries = EntryRepository(database.entryDao(), clock)
        feeds = FeedRepository(
            feedDao = database.feedDao(),
            entryDao = database.entryDao(),
            fetcher = FeedFetcher(PerchHttp.client(cacheDir = null)),
            clock = clock,
        )
        feedId = runBlocking { database.feedDao().insert(feed()) }
    }

    @After
    fun tearDown() = database.close()

    /** §0's first shape: fabiensanglard.net ships no body at all. */
    @Test
    fun `opening an entry with no body fetches the article and renders it`() {
        val fixture = ArticleFixtures.noBody.first { it.slug == "fabiensanglard-tb4" }
        val id = seedEntry(link = fixture.url, contentHtml = null)

        showArticle(id, serving(fixture.slug))
        await { stored(id).fullTextAt != null }

        assertThat(stored(id).contentHtml).isNotNull()
        compose.onNodeWithText(fixture.last, substring = true).assertExists()
    }

    /** §0's second shape: a 194-character teaser standing in for the body. */
    @Test
    fun `opening an excerpt-only entry replaces the teaser with the article`() {
        val fixture = ArticleFixtures.excerptOnly.first { it.slug == "gpuopen-adaptive-subdivision" }
        val id = seedEntry(
            link = fixture.url,
            contentHtml = "<p>Learn how fast, crack-free GPU work graph subdivision works.</p>",
            bodyIsExcerpt = true,
        )

        showArticle(id, serving(fixture.slug))
        await { stored(id).fullTextAt != null }

        compose.onNodeWithText(fixture.mid, substring = true).assertExists()
        assertThat(fetches.get()).isEqualTo(1)
    }

    /**
     * The other half of the trigger, and the one that costs bandwidth if it is wrong: a
     * feed that ships the whole article is left entirely alone.
     */
    @Test
    fun `opening an entry whose feed shipped the whole article fetches nothing`() {
        val body = "<p>${"A real article, with commas, and paragraphs. ".repeat(40)}</p>"
        val id = seedEntry(link = "https://example.com/post", contentHtml = body)

        showArticle(id, serving("fabiensanglard-tb4"))
        compose.waitForIdle()

        assertThat(fetches.get()).isEqualTo(0)
        assertThat(stored(id).fullTextAt).isNull()
    }

    /**
     * The heuristic will sometimes call an excerpt an article. *Load full article* is the
     * way out, so it is offered on any body that did not itself come from an extraction.
     */
    @Test
    fun `load full article is offered on a feed-supplied body and fetches when chosen`() {
        val fixture = ArticleFixtures.noBody.first { it.slug == "fabiensanglard-silpheed" }
        val body = "<p>${"A body long enough that nothing fires on its own. ".repeat(40)}</p>"
        val id = seedEntry(link = fixture.url, contentHtml = body)

        showArticle(id, serving(fixture.slug))
        openOverflow()
        compose.onNodeWithTag(ArticleTestTags.LOAD_FULL_TEXT).assertIsEnabled()
        tap(ArticleTestTags.LOAD_FULL_TEXT)
        await { stored(id).fullTextAt != null }

        compose.onNodeWithText(fixture.mid, substring = true).assertExists()
    }

    /** Once the body *did* come from an extraction there is nothing left to load. */
    @Test
    fun `load full article is disabled once the body came from an extraction`() {
        val fixture = ArticleFixtures.noBody.first { it.slug == "fabiensanglard-tb4" }
        val id = seedEntry(link = fixture.url, contentHtml = null)

        showArticle(id, serving(fixture.slug))
        await { stored(id).fullTextAt != null }
        openOverflow()

        compose.onNodeWithTag(ArticleTestTags.LOAD_FULL_TEXT).assertIsNotEnabled()
    }

    /**
     * A page that cannot be fetched is not an error state — it is exactly the state the
     * app was in before U10, so the reader gets the summary and the button to the site.
     */
    @Test
    fun `a failed fetch leaves the read-on-the-web fallback in place`() {
        val id = seedEntry(link = "https://example.com/post", contentHtml = null)

        showArticle(id, PageFetcher { fetches.incrementAndGet(); null })
        compose.waitForIdle()

        assertThat(fetches.get()).isEqualTo(1)
        assertThat(stored(id).fullTextAt).isNull()
        compose.onNodeWithTag(ArticleTestTags.READ_ON_WEB).assertIsDisplayed()
    }

    // ---- harness ---------------------------------------------------------------

    private fun showArticle(entryId: Long, fetcher: PageFetcher) {
        val viewModel = ArticleViewModel(
            entries = entries,
            feeds = feeds,
            articleText = ArticleTextRepository(database.entryDao(), fetcher, clock),
            entryId = entryId,
            zone = ZoneOffset.UTC,
        )
        compose.setContent {
            PerchTheme(dynamicColor = false) {
                ArticleScreen(viewModel = viewModel, onBack = {})
            }
        }
        await { viewModel.state.value !is ArticleUiState.Loading }
    }

    /** A fetcher that answers every request with one harvested page, and counts calls. */
    private fun serving(slug: String): PageFetcher {
        val bytes = File(ArticleFixtures.dir(), "$slug.html").readBytes()
        return PageFetcher { url ->
            fetches.incrementAndGet()
            FetchedPage(bytes, "text/html; charset=utf-8", url)
        }
    }

    /** A dropdown is its own window; an injected tap never reaches it (NOTES.md). */
    private fun openOverflow() = tap(ArticleTestTags.OVERFLOW)

    private fun tap(testTag: String) {
        compose.onNodeWithTag(testTag).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (predicate()) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out waiting for the database")
    }

    private fun stored(entryId: Long): EntryEntity =
        runBlocking { database.entryDao().findById(entryId)!! }

    private fun seedEntry(
        link: String?,
        contentHtml: String?,
        bodyIsExcerpt: Boolean = false,
    ): Long = runBlocking {
        database.entryDao().insert(
            EntryEntity(
                feedId = feedId,
                guid = "guid-${link.hashCode()}",
                title = "An entry",
                link = link,
                author = null,
                publishedAt = now.minusSeconds(DAY).toEpochMilli(),
                publishedIsEstimated = false,
                summary = "A short summary.",
                contentHtml = contentHtml,
                imageUrl = null,
                readAt = null,
                bodyIsExcerpt = bodyIsExcerpt,
                fetchedAt = now.toEpochMilli(),
            ),
        )
    }

    private fun feed() = FeedEntity(
        feedUrl = "https://example.com/feed.xml",
        siteUrl = "https://example.com",
        title = "Example",
        customTitle = null,
        faviconUrl = null,
        etag = null,
        lastModified = null,
        lastFetchedAt = null,
        lastSuccessAt = null,
        lastError = null,
        addedAt = 0L,
    )

    private companion object {
        const val DAY = 24 * 3_600L
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L
    }
}
