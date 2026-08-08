package dev.mkiros.perch.ui.article

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.ui.theme.PerchTheme
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The reading surface end to end (T25): what the screen loads, what opening an entry does
 * to it, and where "open in browser" sends the reader.
 *
 * Seeded straight into an in-memory database like the home tests, so the sanitized HTML
 * the repository would have written is what the lowering and the renderer actually see.
 */
@RunWith(RobolectricTestRunner::class)
class ArticleScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: PerchDatabase
    private lateinit var container: AppContainer

    private val now = Instant.parse("2026-08-07T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PerchDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        container = AppContainer(
            database = database,
            httpClient = PerchHttp.client(cacheDir = null),
            clock = clock,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `the article shows its headline, its byline, and its body`() {
        val feedId = seedFeed(title = "Null Program")
        val entryId = seedEntry(
            feedId = feedId,
            title = "An Async Runtime in C",
            author = "Chris Wellons",
            contentHtml = "<p>Coroutines without a language runtime.</p><h2>The trick</h2>",
        )

        showArticle(entryId)

        compose.onNodeWithTag(ArticleTestTags.HEADLINE).assertTextEquals("An Async Runtime in C")
        compose.onNodeWithTag(ArticleTestTags.BYLINE)
            .assertTextEquals("NULL PROGRAM · CHRIS WELLONS · 3 AUG 2026")
        compose.onNodeWithText("Coroutines without a language runtime.").assertIsDisplayed()
        compose.onNodeWithText("The trick").assertIsDisplayed()
    }

    @Test
    fun `a source the reader renamed appears in the byline under that name`() {
        val feedId = seedFeed(title = "nullprogram.com", customTitle = "Chris Wellons")
        val entryId = seedEntry(feedId = feedId, title = "Practical libc-free threading")

        showArticle(entryId)

        compose.onNodeWithTag(ArticleTestTags.BYLINE)
            .assertTextEquals("CHRIS WELLONS · 3 AUG 2026")
    }

    @Test
    fun `opening an entry marks it read`() {
        val feedId = seedFeed(title = "Null Program")
        val entryId = seedEntry(feedId = feedId, title = "An Async Runtime in C")
        assertThat(entry(entryId).isRead).isFalse()

        showArticle(entryId)
        await { entry(entryId).isRead }

        assertThat(entry(entryId).isRead).isTrue()
        assertThat(entry(entryId).readAt).isEqualTo(now.toEpochMilli())
    }

    @Test
    fun `open in browser hands the entry's link to the system`() {
        val feedId = seedFeed(title = "Null Program")
        val entryId = seedEntry(
            feedId = feedId,
            title = "An Async Runtime in C",
            link = "https://nullprogram.com/blog/2026/08/03/",
        )

        showArticle(entryId)
        tap(ArticleTestTags.OPEN_IN_BROWSER)

        val intent = shadowOf(compose.activity).nextStartedActivity
        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.data.toString()).isEqualTo("https://nullprogram.com/blog/2026/08/03/")
    }

    @Test
    fun `an entry with no body offers its summary and a way to read it on the web`() {
        val feedId = seedFeed(title = "Null Program")
        val entryId = seedEntry(
            feedId = feedId,
            title = "An Async Runtime in C",
            summary = "Coroutines without a language runtime, in about 200 lines.",
            contentHtml = null,
            link = "https://nullprogram.com/blog/2026/08/03/",
        )

        showArticle(entryId)

        compose.onNodeWithText("Coroutines without a language runtime, in about 200 lines.")
            .assertIsDisplayed()
        tap(ArticleTestTags.READ_ON_WEB)

        val intent = shadowOf(compose.activity).nextStartedActivity
        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.data.toString()).isEqualTo("https://nullprogram.com/blog/2026/08/03/")
    }

    @Test
    fun `a summary the body already opens with is not repeated as a standfirst`() {
        val feedId = seedFeed(title = "Null Program")
        val entryId = seedEntry(
            feedId = feedId,
            title = "An Async Runtime in C",
            summary = "Coroutines without a language runtime",
            contentHtml = "<p>Coroutines without a language runtime, in about 200 lines.</p>",
        )

        showArticle(entryId)

        compose.onNodeWithTag(ArticleTestTags.STANDFIRST).assertDoesNotExist()
        compose.onNodeWithText("Coroutines without a language runtime, in about 200 lines.")
            .assertIsDisplayed()
    }

    /**
     * The summary is flattened prose, so it routinely runs past the body's first block —
     * across a heading and on into the second paragraph. T32's live run found this on
     * four of its five sampled sources: the opening was printed twice, once in italic and
     * once in body text, which is the loudest way an article can fail to look like the
     * one next to it.
     */
    @Test
    fun `a summary running past the body's first block is still not repeated`() {
        val feedId = seedFeed(title = "Embedded in Academia")
        val entryId = seedEntry(
            feedId = feedId,
            title = "Bugfinding for LLVM's AArch64 Backend",
            summary = "Overview [Co-authored by Ryan Berger.] An optimizing compiler has " +
                "three parts. The problem, as they put it, is the backend…",
            contentHtml = "<h2>Overview</h2><p>[Co-authored by Ryan Berger.]</p>" +
                "<p>An optimizing compiler has three parts.</p>" +
                "<blockquote><p>The problem, as they put it,</p>" +
                "<img src=\"https://example.com/figure.png\" alt=\"\">" +
                "<p>is the backend.</p></blockquote>",
        )

        showArticle(entryId)

        compose.onNodeWithTag(ArticleTestTags.STANDFIRST).assertDoesNotExist()
        compose.onNodeWithText("[Co-authored by Ryan Berger.]").assertIsDisplayed()
    }

    @Test
    fun `a summary that adds something to the body runs as a standfirst`() {
        val feedId = seedFeed(title = "Null Program")
        val entryId = seedEntry(
            feedId = feedId,
            title = "An Async Runtime in C",
            summary = "Why the obvious approach deadlocks.",
            contentHtml = "<p>Start with a plain function pointer.</p>",
        )

        showArticle(entryId)

        compose.onNodeWithTag(ArticleTestTags.STANDFIRST)
            .assertTextEquals("Why the obvious approach deadlocks.")
    }

    @Test
    fun `an entry that no longer exists says so instead of showing a blank page`() {
        showArticle(entryId = 404L)

        compose.onNodeWithText("This entry is no longer available").assertIsDisplayed()
    }

    // ---- harness ---------------------------------------------------------------

    private fun showArticle(entryId: Long) {
        val viewModel = ArticleViewModel(
            entries = container.entries,
            feeds = container.feeds,
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

    /**
     * Taps by the node's own click semantics rather than a synthesised touch — the same
     * reason `HomeScreenTest` does: under Robolectric an injected gesture does not
     * reliably reach a node that laid out inside a scrolling container.
     */
    private fun tap(testTag: String) {
        compose.onNodeWithTag(testTag).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    /**
     * Waits in *wall-clock* time, the way `HomeScreenTest.awaitState` does and for the
     * same reason: `compose.waitUntil` only advances Compose's virtual clock, so its
     * whole timeout can burn in microseconds without Room's query executor — a genuine
     * background thread — ever being scheduled. Loading an entry is one such query.
     */
    private fun await(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (predicate()) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out waiting for the database")
    }

    private fun entry(entryId: Long): EntryEntity = runBlocking {
        database.entryDao().observeAll().first().first { it.id == entryId }
    }

    private fun seedFeed(title: String, customTitle: String? = null): Long = runBlocking {
        database.feedDao().insert(
            FeedEntity(
                feedUrl = "https://example.com/${title.hashCode()}/feed.xml",
                siteUrl = "https://example.com",
                title = title,
                customTitle = customTitle,
                faviconUrl = null,
                etag = null,
                lastModified = null,
                lastFetchedAt = null,
                lastSuccessAt = null,
                lastError = null,
                addedAt = 0L,
            ),
        )
    }

    private fun seedEntry(
        feedId: Long,
        title: String,
        author: String? = null,
        summary: String? = "A short summary.",
        contentHtml: String? = "<p>The body of the post.</p>",
        link: String? = "https://example.com/post",
    ): Long = runBlocking {
        database.entryDao().insert(
            EntryEntity(
                feedId = feedId,
                guid = "guid-${title.hashCode()}",
                title = title,
                link = link,
                author = author,
                publishedAt = now.minusSeconds(4 * DAY).toEpochMilli(),
                publishedIsEstimated = false,
                summary = summary,
                contentHtml = contentHtml,
                imageUrl = null,
                isRead = false,
                readAt = null,
                fetchedAt = now.toEpochMilli(),
            ),
        )
    }

    private companion object {
        const val DAY = 24 * 3_600L
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
