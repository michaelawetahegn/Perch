package dev.mkiros.perch.ui.article

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.core.content.IntentCompat
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.repo.ArticleTextRepository
import dev.mkiros.perch.support.PerchRule
import dev.mkiros.perch.support.testEntry
import dev.mkiros.perch.support.testFeed
import dev.mkiros.perch.ui.screenshot.awaitInRealTime
import dev.mkiros.perch.ui.theme.Dimens
import dev.mkiros.perch.ui.theme.PerchTheme
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
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

    private val now = Instant.parse("2026-08-07T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @get:Rule(order = 1)
    val perch = PerchRule(clock = clock)

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
        // V08 split the line: the source is its own segment because it is the only one
        // that goes anywhere. Together they still read `SOURCE · AUTHOR · DATE`.
        compose.onNodeWithTag(ArticleTestTags.SOURCE).assertTextEquals("NULL PROGRAM")
        compose.onNodeWithTag(ArticleTestTags.BYLINE)
            .assertTextEquals("CHRIS WELLONS · 3 AUG 2026")
        compose.onNodeWithText("Coroutines without a language runtime.").assertIsDisplayed()
        compose.onNodeWithText("The trick").assertIsDisplayed()
    }

    @Test
    fun `a source the reader renamed appears in the byline under that name`() {
        val feedId = seedFeed(title = "nullprogram.com", customTitle = "Chris Wellons")
        val entryId = seedEntry(feedId = feedId, title = "Practical libc-free threading")

        showArticle(entryId)

        compose.onNodeWithTag(ArticleTestTags.SOURCE).assertTextEquals("CHRIS WELLONS")
        // The author matched the source, so the source segment took it and the rest of
        // the line is the date alone rather than the same name twice.
        compose.onNodeWithTag(ArticleTestTags.BYLINE).assertTextEquals("3 AUG 2026")
    }

    // ---- V08 (issue #10): the source's name is the way into its list ----------------

    /**
     * The reader's complaint was that the name at the top does nothing. What it must do
     * is say *which* source, and it must be a control while it does — a bare text with a
     * click on it is invisible to a screen reader and to a thumb.
     */
    @Test
    fun `tapping the source name asks for that source's list`() {
        val feedId = seedFeed(title = "Null Program")
        val entryId = seedEntry(feedId = feedId, title = "An Async Runtime in C")
        val opened = mutableListOf<Long>()

        showArticle(entryId, onOpenSource = { opened += it })
        compose.onNodeWithTag(ArticleTestTags.SOURCE).performClick()

        assertThat(opened).containsExactly(feedId)
    }

    @Test
    fun `the source name is a button with a hit area a thumb can find`() {
        val feedId = seedFeed(title = "Null Program")
        val entryId = seedEntry(feedId = feedId, title = "An Async Runtime in C")

        showArticle(entryId)

        val node = compose.onNodeWithTag(ArticleTestTags.SOURCE).fetchSemanticsNode()
        assertThat(node.config[SemanticsProperties.Role]).isEqualTo(Role.Button)
        with(compose.density) {
            assertThat(node.size.height.toDp().value).isAtLeast(Dimens.touchTarget.value)
        }
    }

    /** A feed that shipped no title has nothing to tap, and the line just starts later. */
    @Test
    fun `a source with no name at all leaves the byline without a first segment`() {
        val feedId = seedFeed(title = "")
        val entryId = seedEntry(
            feedId = feedId,
            title = "An Async Runtime in C",
            author = "Chris Wellons",
        )

        showArticle(entryId)

        compose.onNodeWithTag(ArticleTestTags.SOURCE).assertDoesNotExist()
        compose.onNodeWithTag(ArticleTestTags.BYLINE)
            .assertTextEquals("CHRIS WELLONS · 3 AUG 2026")
    }

    // ---- S06 (issue #25): a guessed date reads as a guess ---------------------------

    /**
     * The date in the byline is the same claim the row makes, so it is marked the same
     * way (PLAN-9 §0.7). A pasted link usually has no date of its own — the byline saying
     * `3 AUG 2026` flatly is Perch reporting the moment it fetched the page as though the
     * author had written it then.
     */
    @Test
    fun `a byline whose date was guessed marks it with a tilde`() {
        val feedId = seedFeed(title = "Null Program")
        val entryId = seedEntry(
            feedId = feedId,
            title = "An Async Runtime in C",
            author = "Chris Wellons",
            publishedIsEstimated = true,
        )

        showArticle(entryId)

        compose.onNodeWithTag(ArticleTestTags.BYLINE)
            .assertTextEquals("CHRIS WELLONS · ~3 AUG 2026")
    }

    @Test
    fun `a byline whose date came from the article carries no tilde`() {
        val feedId = seedFeed(title = "Null Program")
        val entryId = seedEntry(feedId = feedId, title = "An Async Runtime in C")

        showArticle(entryId)

        compose.onNodeWithTag(ArticleTestTags.BYLINE).assertTextEquals("3 AUG 2026")
    }

    // ---- S05 (issue #32): the byline is a subheading of two lines --------------------

    /**
     * The reader's screenshot: a long source and a long author overran the measure, and
     * because the line was a `Row` of independently-measured segments only the *last* one
     * wrapped — it folded under the source and the source sat vertically centred against
     * the two-line block beside it. Nothing was cut off, so the only thing wrong with it
     * was the shape.
     *
     * The shape is what this pins: source above, the rest beneath it, both starting at
     * the same margin, whatever the two of them measure.
     */
    @Test
    fun `a long byline stacks the source above the rest instead of wrapping around it`() {
        val feedId = seedFeed(title = "Global Investigative Journalism Network")
        val entryId = seedEntry(
            feedId = feedId,
            title = "Investigating Inside Conflict Zones in Africa",
            author = "Benon Herbert Oluka and Rowan Philp",
        )

        showArticle(entryId)

        val source = compose.onNodeWithTag(ArticleTestTags.SOURCE).fetchSemanticsNode()
        val rest = compose.onNodeWithTag(ArticleTestTags.BYLINE).fetchSemanticsNode()
        assertThat(source.boundsInRoot.bottom).isAtMost(rest.boundsInRoot.top)
        assertThat(source.boundsInRoot.left).isWithin(0.5f).of(rest.boundsInRoot.left)
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
    fun `sharing an article hands the system a send carrying its link`() {
        val feedId = seedFeed(title = "Null Program")
        val entryId = seedEntry(
            feedId = feedId,
            title = "An Async Runtime in C",
            link = "https://nullprogram.com/blog/2026/08/03/",
        )

        showArticle(entryId)
        tap(ArticleTestTags.SHARE)

        // §0: the sheet is the OS's, so what leaves Perch is a chooser — the payload we
        // care about is the `ACTION_SEND` wrapped inside it.
        val chooser = shadowOf(compose.activity).nextStartedActivity
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send.getStringExtra(Intent.EXTRA_TEXT))
            .isEqualTo("https://nullprogram.com/blog/2026/08/03/")
        assertThat(send.getStringExtra(Intent.EXTRA_SUBJECT)).isEqualTo("An Async Runtime in C")
    }

    @Test
    fun `copy link puts the entry's link on the clipboard`() {
        val feedId = seedFeed(title = "Null Program")
        val entryId = seedEntry(
            feedId = feedId,
            title = "An Async Runtime in C",
            link = "https://nullprogram.com/blog/2026/08/03/",
        )

        showArticle(entryId)
        tap(ArticleTestTags.OVERFLOW)
        tap(ArticleTestTags.COPY_LINK)

        assertThat(clipboardText()).isEqualTo("https://nullprogram.com/blog/2026/08/03/")
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

    // ---- the top bar's two toggles (U09) ---------------------------------------

    @Test
    fun `the like toggle files the entry under Liked and flips its own label`() {
        val entryId = seedEntry(seedFeed(title = "Null Program"), title = "An Async Runtime in C")
        showArticle(entryId)

        // Off: the outlined glyph, and the verb is the direction it is about to go.
        compose.onNodeWithContentDescription("Like").assertExists()
        tap(ArticleTestTags.LIKE)

        await { runBlocking { perch.container.entries.find(entryId) }?.isStarred == true }
        compose.onNodeWithContentDescription("Remove from Liked").assertExists()
    }

    @Test
    fun `the save toggle files the entry under To-Read and flips its own label`() {
        val entryId = seedEntry(seedFeed(title = "Null Program"), title = "An Async Runtime in C")
        showArticle(entryId)

        compose.onNodeWithContentDescription("Save for later").assertExists()
        tap(ArticleTestTags.SAVE)

        await { runBlocking { perch.container.entries.find(entryId) }?.isSaved == true }
        compose.onNodeWithContentDescription("Remove from To-Read").assertExists()
    }

    /**
     * Opening an article marks it read, and *Liked* is permanent — so a second visit must
     * come back showing the toggle already on rather than reset to off.
     */
    @Test
    fun `a liked entry opens with its toggle already on`() {
        val entryId = seedEntry(seedFeed(title = "Null Program"), title = "An Async Runtime in C")
        runBlocking { perch.container.entries.setLiked(entryId, isLiked = true) }

        showArticle(entryId)

        compose.onNodeWithContentDescription("Remove from Liked").assertExists()
    }

    // ---- E01 (issue #65): an article reopens where the reader stopped ----------------

    /**
     * The reader's words: "when you go back or you close the app and then you come back to
     * the article, you can start reading where you left off." Leaving is what writes the
     * position, so the screen is disposed — not just navigated away from — and shown again
     * through a fresh view model, the way a second visit really arrives.
     */
    @Test
    fun `leaving an article partway and reopening it resumes where the reader stopped`() {
        val entryId = seedEntry(
            feedId = seedFeed(title = "Null Program"),
            title = "A long read",
            contentHtml = (1..30).joinToString("") { "<p>Paragraph $it of a long article.</p>" },
        )

        showArticle(entryId)
        scrollingBody().performTouchInput { swipeUp() }
        await { entry(entryId).scrollPosition > 0 }
        val stopped = entry(entryId).scrollPosition
        leaveArticle()

        showArticle(entryId)

        assertThat(restoredOffset()).isEqualTo(stopped.toFloat())
    }

    @Test
    fun `an entry that no longer exists says so instead of showing a blank page`() {
        showArticle(entryId = 404L)

        compose.onNodeWithText("This entry is no longer available").assertIsDisplayed()
    }

    // ---- harness ---------------------------------------------------------------

    /**
     * The screen on show, or null between visits. Content is set once per test and reads
     * this, so a test can leave an article (disposing the screen, which is what writes the
     * reading position) and come back to it through a fresh view model, as navigation does.
     */
    private val visit = mutableStateOf<ArticleViewModel?>(null)
    private var contentSet = false

    private fun showArticle(entryId: Long, onOpenSource: (Long) -> Unit = {}) {
        val viewModel = ArticleViewModel(
            entries = perch.container.entries,
            feeds = perch.container.feeds,
            // U10's extraction is deliberately inert here: these tests are about what the
            // renderer does with a body, and every one of their bodies is short enough to
            // trip the trigger. `ArticleFullTextTest` is where the fetch is exercised.
            articleText = ArticleTextRepository(perch.database.entryDao(), { null }, clock),
            entryId = entryId,
            zone = ZoneOffset.UTC,
        )
        if (!contentSet) {
            contentSet = true
            compose.setContent {
                PerchTheme(dynamicColor = false) {
                    visit.value?.let { shown ->
                        ArticleScreen(viewModel = shown, onBack = {}, onOpenSource = onOpenSource)
                    }
                }
            }
        }
        visit.value = viewModel
        await { viewModel.state.value !is ArticleUiState.Loading }
    }

    /**
     * Leaves the article, and waits for the leaving write to land.
     *
     * Disposing the screen is what writes the reading position (E01), and the write is
     * deliberately `NonCancellable` — so at the end of a test it would otherwise outlive
     * [PerchRule]'s close and reach a closed connection pool, which JUnit reports against
     * whichever test runs next (`UncaughtExceptionsBeforeTest`). Idling the main looper gets
     * the write submitted; the no-op update behind it is queued on Room's *serial*
     * transaction executor, so returning from it means the real write has completed.
     */
    @After
    fun leaveArticle() {
        visit.value = null
        compose.waitForIdle()
        runBlocking { perch.database.entryDao().setScrollPosition(id = 0L, scrollPosition = 0) }
    }

    /** The body's own scrolling container — the one vertical scroller on the screen. */
    private fun scrollingBody() =
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))

    /** Where the body is scrolled to, read back from the scroller's semantics. */
    private fun restoredOffset(): Float =
        scrollingBody().fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()

    /**
     * Taps by the node's own click semantics rather than a synthesised touch — the same
     * reason `HomeScreenTest` does: under Robolectric an injected gesture does not
     * reliably reach a node that laid out inside a scrolling container.
     */
    private fun clipboardText(): String? {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    }

    private fun tap(testTag: String) {
        compose.onNodeWithTag(testTag).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun await(predicate: () -> Boolean) =
        compose.awaitInRealTime("the database to satisfy the test's predicate", predicate = predicate)

    private fun entry(entryId: Long): EntryEntity = runBlocking {
        perch.database.entryDao().observeAll().first().first { it.id == entryId }
    }

    private fun seedFeed(title: String, customTitle: String? = null): Long = runBlocking {
        perch.database.feedDao().insert(
            testFeed(
                title = title,
                customTitle = customTitle,
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
        publishedIsEstimated: Boolean = false,
    ): Long = runBlocking {
        perch.database.entryDao().insert(
            testEntry(
                feedId = feedId,
                title = title,
                link = link,
                author = author,
                publishedAt = now.minusSeconds(4 * DAY).toEpochMilli(),
                publishedIsEstimated = publishedIsEstimated,
                summary = summary,
                contentHtml = contentHtml,
                isRead = false,
                fetchedAt = now.toEpochMilli(),
            ),
        )
    }

    private companion object {
        const val DAY = 24 * 3_600L
    }
}
