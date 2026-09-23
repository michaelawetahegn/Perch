package dev.mkiros.perch.ui.article

import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mkiros.perch.ui.article.document.DocumentPosition
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.document.DocumentFixtures
import dev.mkiros.perch.data.repo.ArticleTextRepository
import dev.mkiros.perch.support.PerchRule
import dev.mkiros.perch.ui.article.document.DocumentTextColumnKey
import dev.mkiros.perch.ui.article.document.DocumentZoom
import dev.mkiros.perch.ui.screenshot.awaitInRealTime
import dev.mkiros.perch.ui.theme.PerchTheme
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The document reader (PLAN-13 §0.6): a stored PDF drawn as its own pages, each at its own
 * aspect and followed by a separator, under a header of headline, byline and strip.
 *
 * Pages come from [dev.mkiros.perch.support.FixtureRasterizer] over `fixtures/documents/` —
 * `PdfRenderer` cannot run under Robolectric (§0.5).
 */
@RunWith(RobolectricTestRunner::class)
class DocumentBodyTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-09-21T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @get:Rule(order = 1)
    val perch = PerchRule(clock = clock)

    @Test
    fun `a document shows its headline, byline, strip and first page`() {
        showArticle(seedDocument("ssrn-6191618"))

        compose.onNodeWithTag(ArticleTestTags.HEADLINE)
            .assertTextEquals("Who Profits from Prediction? Execution, not Information")
        compose.onNodeWithTag(ArticleTestTags.BYLINE, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(ArticleTestTags.DOCUMENT_STRIP, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("112 pages · ${sizeOf(870_451)} · saved offline").assertIsDisplayed()
        compose.onNodeWithTag(ArticleTestTags.STANDFIRST).assertDoesNotExist()
        awaitPageBitmap(0)
        compose.onNodeWithTag("${ArticleTestTags.DOCUMENT_PAGE_IMAGE}:0", useUnmergedTree = true)
            .assertIsDisplayed()

        compose.onNodeWithTag(ArticleTestTags.OVERFLOW).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag(ArticleTestTags.SHARE).assertExists()
        compose.onNodeWithTag(ArticleTestTags.LOAD_FULL_TEXT).assertDoesNotExist()
    }

    @Test
    fun `each page takes its own aspect, not a letter-sized guess`() {
        showArticle(seedDocument("mixed-sizes"))

        // Page two of mixed-sizes is landscape, 3:2 (FixtureRasterizer's hard-coded sizes).
        compose.onNodeWithTag(ArticleTestTags.DOCUMENT).performScrollToIndex(2)
        val bounds = compose.onNodeWithTag("${ArticleTestTags.DOCUMENT_PAGE}:1").getUnclippedBoundsInRoot()
        val aspect = (bounds.right - bounds.left) / (bounds.bottom - bounds.top)
        assertThat(aspect).isWithin(0.02f).of(1.5f)
    }

    @Test
    fun `every page is followed by a separator`() {
        showArticle(seedDocument("ssrn-6191618"))

        assertSeparatorUnder(page = 0)
        // Item N is page N; item 0 is the header.
        compose.onNodeWithTag(ArticleTestTags.DOCUMENT).performScrollToIndex(2)
        assertSeparatorUnder(page = 1)

        compose.onNodeWithTag(ArticleTestTags.DOCUMENT).performScrollToIndex(112)
        compose.onNodeWithTag("${ArticleTestTags.DOCUMENT_SEPARATOR}:111").assertExists()
    }

    @Test
    fun `a document whose file is gone says so`() {
        val feed = perch.seedFeed(title = "Saved")
        val id = perch.seedEntry(
            feed,
            title = "A vanished paper",
            link = null,
            contentHtml = null,
            documentPath = "documents/never-stored.pdf",
        )
        showArticle(id)

        compose.onNodeWithTag(ArticleTestTags.DOCUMENT_GONE)
            .assertTextEquals("This document is no longer stored. Save it again to fetch it.")
        compose.onNodeWithTag(ArticleTestTags.DOCUMENT).assertDoesNotExist()
    }

    @Test
    fun `scrolling to page two raises the toast and it fades`() {
        showArticle(seedDocument("ssrn-6191618"))

        compose.onNodeWithTag(ArticleTestTags.DOCUMENT).performScrollToIndex(2)
        compose.onNodeWithTag(ArticleTestTags.DOCUMENT_TOAST, useUnmergedTree = true)
            .assertTextEquals("Page 2 of 112")

        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithTag(ArticleTestTags.DOCUMENT_TOAST, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `the toast never shows on open`() {
        val id = seedDocument("ssrn-6191618")
        runBlocking { perch.database.entryDao().setScrollPosition(id = id, scrollPosition = 3) }
        showArticle(id)
        awaitPageBitmap(2)

        compose.onNodeWithTag(ArticleTestTags.DOCUMENT_TOAST, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `leaving the screen writes the item at the top and how far into it`() {
        val id = seedDocument("ssrn-6191618")
        showArticle(id)

        compose.onNodeWithTag(ArticleTestTags.DOCUMENT).performScrollToIndex(2)
        leaveArticle()

        val entry = runBlocking { perch.database.entryDao().findById(id) }!!
        val stored = DocumentPosition.decode(entry.scrollPosition)
        assertThat(stored.item).isEqualTo(2)
        assertThat(stored.offsetIn(1_000)).isEqualTo(0)
    }

    @Test
    fun `a document reopens at the exact offset it was left at, across two pages`() {
        val id = seedDocument("ssrn-6191618")
        showArticle(id)

        // Page two's bottom fifth and the top of page three — not a page boundary.
        compose.onNodeWithTag(ArticleTestTags.DOCUMENT).performScrollToIndex(2)
        val pageHeight = pageBounds(1).let { (it.bottom - it.top).value } * density()
        compose.onNodeWithTag(ArticleTestTags.DOCUMENT).performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, pageHeight * 0.8f)
        }
        compose.waitForIdle()
        val left = topPage()
        assertThat(left.first).isEqualTo(1)
        assertThat(left.second).isLessThan(-pageHeight * 0.7f)

        leaveArticle()
        showArticle(id)

        assertTopIs(left)
    }

    @Test
    fun `a fling still running when the screen stops is saved where the screen stopped`() {
        val id = seedDocument("ssrn-6191618")
        showArticle(id)

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(ArticleTestTags.DOCUMENT).performTouchInput { swipeUp(durationMillis = 60) }
        compose.mainClock.advanceTimeBy(FRAME_MS * 4)
        val atBack = topPage()

        // Back pops the entry: it stops at once, while the exit transition keeps it drawn
        // and the fling keeps going underneath until the composition leaves.
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.mainClock.advanceTimeBy(2_000)
        assertThat(topPage()).isNotEqualTo(atBack)
        visit.value = null
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        showArticle(id)

        assertTopIs(atBack)
    }

    @Test
    fun `a fling that settles before leaving is saved where it settled`() {
        val id = seedDocument("ssrn-6191618")
        showArticle(id)

        compose.onNodeWithTag(ArticleTestTags.DOCUMENT).performTouchInput { swipeUp(durationMillis = 60) }
        compose.waitForIdle()
        val settled = topPage()
        leaveArticle()

        showArticle(id)

        assertTopIs(settled)
    }

    @Test
    fun `a document reopens at the page it stopped on`() {
        val id = seedDocument("ssrn-6191618")
        runBlocking { perch.database.entryDao().setScrollPosition(id = id, scrollPosition = 3) }
        showArticle(id)

        // Item N is page N, so page three (index 2) is the first thing on screen.
        compose.onNodeWithTag(ArticleTestTags.HEADLINE).assertDoesNotExist()
        val list = compose.onNodeWithTag(ArticleTestTags.DOCUMENT).getUnclippedBoundsInRoot()
        val page = compose.onNodeWithTag("${ArticleTestTags.DOCUMENT_PAGE}:2").getUnclippedBoundsInRoot()
        assertThat(page.top.value).isWithin(0.5f).of(list.top.value)
    }

    @Test
    fun `a double tap widens the pages to the text column`() {
        showArticle(seedDocument("letter-margins"))
        awaitTextColumn()
        val list = compose.onNodeWithTag(ArticleTestTags.DOCUMENT).getUnclippedBoundsInRoot()
        val before = pageBounds(0)

        compose.onNodeWithTag(ArticleTestTags.DOCUMENT).performTouchInput { doubleClick() }
        compose.waitForIdle()

        // letter-margins' column is ~0.80 of the page (TextColumnTest), so ~1.25×.
        val after = pageBounds(0)
        val factor = (after.right - after.left) / (before.right - before.left)
        assertThat(factor).isWithin(0.07f).of(1.25f)
        assertThat(after.left.value).isLessThan(list.left.value)
    }

    @Test
    fun `a second double tap returns to fit`() {
        showArticle(seedDocument("letter-margins"))
        awaitTextColumn()
        val before = pageBounds(0)

        compose.onNodeWithTag(ArticleTestTags.DOCUMENT).performTouchInput { doubleClick() }
        compose.mainClock.advanceTimeBy(DOUBLE_TAP_WINDOW_MS)
        compose.onNodeWithTag(ArticleTestTags.DOCUMENT).performTouchInput { doubleClick() }
        compose.waitForIdle()

        assertThat(pageBounds(0)).isEqualTo(before)
    }

    @Test
    fun `a pinch past the maximum settles at the maximum`() {
        showArticle(seedDocument("letter-margins"))
        val before = pageBounds(0)

        compose.onNodeWithTag(ArticleTestTags.DOCUMENT).performTouchInput {
            pinch(
                start0 = center - Offset(SPREAD, 0f),
                end0 = center - Offset(SPREAD * PINCH_FACTOR, 0f),
                start1 = center + Offset(SPREAD, 0f),
                end1 = center + Offset(SPREAD * PINCH_FACTOR, 0f),
            )
        }
        compose.waitForIdle()

        val after = pageBounds(0)
        val factor = (after.right - after.left) / (before.right - before.left)
        assertThat(factor).isWithin(0.01f).of(DocumentZoom.MAX_SCALE)
    }

    // ---- harness ---------------------------------------------------------------

    private val visit = mutableStateOf<ArticleViewModel?>(null)
    private var contentSet = false

    /** The article's own lifecycle, as the back stack entry gives it one: back stops it first. */
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun seedDocument(slug: String): Long {
        val fixture = DocumentFixtures.manifest().first { it.slug == slug }
        val stored = perch.newDocument()
        fixture.file.copyTo(stored, overwrite = true)
        return perch.seedEntry(
            perch.seedFeed(title = "Saved"),
            title = fixture.title ?: slug,
            link = null,
            author = "Joshua Della Vedova",
            contentHtml = null,
            documentPath = perch.container.documents.relativize(stored),
        )
    }

    private fun showArticle(entryId: Long) {
        val viewModel = ArticleViewModel(
            entries = perch.container.entries,
            feeds = perch.container.feeds,
            articleText = ArticleTextRepository(perch.database.entryDao(), { null }, clock),
            entryId = entryId,
            zone = ZoneOffset.UTC,
            rasterizer = perch.container.rasterizer,
            documents = perch.container.documents,
        )
        if (!contentSet) {
            contentSet = true
            compose.setContent {
                PerchTheme(dynamicColor = false) {
                    visit.value?.let { shown ->
                        CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                            ArticleScreen(viewModel = shown, onBack = {})
                        }
                    }
                }
            }
        }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        visit.value = viewModel
        compose.awaitInRealTime("the article to load") { viewModel.state.value !is ArticleUiState.Loading }
        compose.waitForIdle()
    }

    /** A 2 dp rule the width of the page, directly beneath it (§0.6). */
    private fun assertSeparatorUnder(page: Int) {
        val pageBounds = compose.onNodeWithTag("${ArticleTestTags.DOCUMENT_PAGE}:$page")
            .getUnclippedBoundsInRoot()
        val rule = compose.onNodeWithTag("${ArticleTestTags.DOCUMENT_SEPARATOR}:$page")
            .getUnclippedBoundsInRoot()
        assertThat(rule.top.value).isWithin(0.5f).of(pageBounds.bottom.value)
        assertThat((rule.bottom - rule.top).value).isWithin(0.5f).of(2f)
        assertThat(rule.left).isEqualTo(pageBounds.left)
        assertThat(rule.right).isEqualTo(pageBounds.right)
    }

    /**
     * The page whose top is at or above the list's top — the one the screen is inside —
     * and how far above, in pixels (0 or less). Unclipped: the page runs past the list.
     */
    private fun topPage(): Pair<Int, Float> {
        val listTop = compose.onNodeWithTag(ArticleTestTags.DOCUMENT).fetchSemanticsNode().positionInRoot.y
        val tops = compose.onAllNodes(SemanticsMatcher("a document page") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.let(PAGE_TAG::matches) == true
        }, useUnmergedTree = true).fetchSemanticsNodes().associate { node ->
            PAGE_TAG.matchEntire(node.config[SemanticsProperties.TestTag])!!.groupValues[1].toInt() to
                node.positionInRoot.y - listTop
        }
        val page = tops.filterValues { it <= 0.5f }.keys.maxOrNull() ?: tops.keys.min()
        return page to tops.getValue(page)
    }

    /** The same page is at the top, to the pixel. */
    private fun assertTopIs(expected: Pair<Int, Float>) {
        compose.awaitInRealTime("the document to show") {
            compose.onAllNodesWithTagCount(ArticleTestTags.DOCUMENT) > 0
        }
        val now = topPage()
        assertThat(now.first).isEqualTo(expected.first)
        assertThat(now.second).isWithin(1f).of(expected.second)
    }

    private fun density(): Float = ApplicationProvider.getApplicationContext<android.content.Context>()
        .resources.displayMetrics.density

    private fun pageBounds(page: Int) =
        compose.onNodeWithTag("${ArticleTestTags.DOCUMENT_PAGE}:$page").getUnclippedBoundsInRoot()

    /** A double tap before the column is measured is the 2× fallback (§0.6), so wait for it. */
    private fun awaitTextColumn() =
        compose.awaitInRealTime("the text column to be measured") {
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(DocumentTextColumnKey))
                .fetchSemanticsNodes().isNotEmpty()
        }

    private fun awaitPageBitmap(page: Int) =
        compose.awaitInRealTime("page ${page + 1} to render") {
            compose.onAllNodesWithTagCount("${ArticleTestTags.DOCUMENT_PAGE_IMAGE}:$page") > 0
        }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true)
            .fetchSemanticsNodes().size

    private fun sizeOf(bytes: Long): String =
        Formatter.formatShortFileSize(ApplicationProvider.getApplicationContext(), bytes)

    /** Drains the leaving write the way `ArticleScreenTest.leaveArticle` does (E01). */
    @After
    fun leaveArticle() {
        visit.value = null
        compose.waitForIdle()
        runBlocking { perch.database.entryDao().setScrollPosition(id = 0L, scrollPosition = 0) }
    }

    private companion object {
        val PAGE_TAG = Regex("${Regex.escape(ArticleTestTags.DOCUMENT_PAGE)}:(\\d+)")
        const val FRAME_MS = 16L

        /** Half the gap between the pinching fingers, and how far apart they end up (ImageViewerTest). */
        const val SPREAD = 40f
        const val PINCH_FACTOR = 20f

        /** Comfortably past `ViewConfiguration`'s 300 ms double-tap timeout. */
        const val DOUBLE_TAP_WINDOW_MS = 1_000L
    }
}
