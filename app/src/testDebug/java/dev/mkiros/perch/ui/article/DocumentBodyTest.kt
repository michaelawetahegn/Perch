package dev.mkiros.perch.ui.article

import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.document.DocumentFixtures
import dev.mkiros.perch.data.repo.ArticleTextRepository
import dev.mkiros.perch.support.PerchRule
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

    // ---- harness ---------------------------------------------------------------

    private val visit = mutableStateOf<ArticleViewModel?>(null)
    private var contentSet = false

    private fun seedDocument(slug: String): Long {
        val fixture = DocumentFixtures.manifest().first { it.slug == slug }
        val stored = perch.container.documents.newDocument()
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
                    visit.value?.let { shown -> ArticleScreen(viewModel = shown, onBack = {}) }
                }
            }
        }
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
}
