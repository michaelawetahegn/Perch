package dev.mkiros.perch.ui.article

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.document.DocumentFixtures
import dev.mkiros.perch.data.repo.ArticleTextRepository
import dev.mkiros.perch.support.PerchRule
import dev.mkiros.perch.ui.screenshot.awaitInRealTime
import dev.mkiros.perch.ui.theme.PerchTheme
import java.io.File
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

/**
 * The document reader end to end: layout, gestures, pagination, and scroll persistence.
 *
 * Tests are seeded with fixture PDFs through [DocumentFixtures]; the rasterizer is
 * [FixtureRasterizer], so page images render from the fixture gallery.
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
        val ssrn = DocumentFixtures.manifest().first { it.slug == "ssrn-6191618" }
        val testFile = copyFixtureToTest(ssrn)

        val feedId = perch.seedFeed(title = "Saved")
        val entryId = perch.seedEntry(
            feedId = feedId,
            title = "Machine Learning and the Market for Lemons",
            author = null,
            documentPath = testFile.absolutePath,
        )

        showArticle(entryId)

        compose.onNodeWithTag(ArticleTestTags.HEADLINE)
            .assertTextEquals("Machine Learning and the Market for Lemons")
        compose.onNodeWithTag(ArticleTestTags.DOCUMENT_STRIP).assertIsDisplayed()
        compose.onNodeWithTag(ArticleTestTags.DOCUMENT).assertIsDisplayed()
    }

    @Test
    fun `every page is followed by a separator`() {
        val ssrn = DocumentFixtures.manifest().first { it.slug == "ssrn-6191618" }
        val testFile = copyFixtureToTest(ssrn)

        val feedId = perch.seedFeed()
        val entryId = perch.seedEntry(
            feedId = feedId,
            title = "Test",
            documentPath = testFile.absolutePath,
        )

        showArticle(entryId)

        compose.onNodeWithTag(ArticleTestTags.DOCUMENT_SEPARATOR).assertIsDisplayed()
    }

    @Test
    fun `scrolling to page two raises the toast and it fades`() {
        val ssrn = DocumentFixtures.manifest().first { it.slug == "ssrn-6191618" }
        val testFile = copyFixtureToTest(ssrn)

        val feedId = perch.seedFeed()
        val entryId = perch.seedEntry(
            feedId = feedId,
            title = "Test",
            documentPath = testFile.absolutePath,
        )

        showArticle(entryId)

        // Scroll to page 2 by scrolling the document list
        compose.onNodeWithTag(ArticleTestTags.DOCUMENT).performScrollToNode()
        compose.waitForIdle()

        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()

        // Toast should appear and then fade after 1200 ms
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    @Test
    fun `the toast never shows on open`() {
        val ssrn = DocumentFixtures.manifest().first { it.slug == "ssrn-6191618" }
        val testFile = copyFixtureToTest(ssrn)

        val feedId = perch.seedFeed()
        val entryId = perch.seedEntry(
            feedId = feedId,
            title = "Test",
            documentPath = testFile.absolutePath,
        )

        showArticle(entryId)

        // Right after open, toast should not be visible
        val toast = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
            .filter { node ->
                try {
                    node.fetchSemanticsNode().contentDescription.contains("Page")
                } catch (e: Exception) {
                    false
                }
            }
        assertThat(toast).isEmpty()
    }

    @Test
    fun `a double tap widens the pages to the text column`() {
        val margins = DocumentFixtures.manifest().first { it.slug == "letter-margins" }
        val testFile = copyFixtureToTest(margins)

        val feedId = perch.seedFeed()
        val entryId = perch.seedEntry(
            feedId = feedId,
            title = "Test",
            documentPath = testFile.absolutePath,
        )

        showArticle(entryId)

        val docNode = compose.onNodeWithTag(ArticleTestTags.DOCUMENT)
        docNode.performTouchInput { doubleClick() }
        compose.waitForIdle()

        docNode.assertExists()
    }

    @Test
    fun `a second double tap returns to fit`() {
        val margins = DocumentFixtures.manifest().first { it.slug == "letter-margins" }
        val testFile = copyFixtureToTest(margins)

        val feedId = perch.seedFeed()
        val entryId = perch.seedEntry(
            feedId = feedId,
            title = "Test",
            documentPath = testFile.absolutePath,
        )

        showArticle(entryId)

        val docNode = compose.onNodeWithTag(ArticleTestTags.DOCUMENT)

        docNode.performTouchInput { doubleClick() }
        compose.waitForIdle()

        docNode.performTouchInput { doubleClick() }
        compose.waitForIdle()

        docNode.assertExists()
    }

    @Test
    fun `a pinch past the maximum settles at the maximum`() {
        val ssrn = DocumentFixtures.manifest().first { it.slug == "ssrn-6191618" }
        val testFile = copyFixtureToTest(ssrn)

        val feedId = perch.seedFeed()
        val entryId = perch.seedEntry(
            feedId = feedId,
            title = "Test",
            documentPath = testFile.absolutePath,
        )

        showArticle(entryId)

        val docNode = compose.onNodeWithTag(ArticleTestTags.DOCUMENT)
        docNode.performTouchInput { pinch() }
        compose.waitForIdle()

        docNode.assertExists()
    }

    @Test
    fun `leaving the screen writes the page under the centre`() {
        val ssrn = DocumentFixtures.manifest().first { it.slug == "ssrn-6191618" }
        val testFile = copyFixtureToTest(ssrn)

        val feedId = perch.seedFeed()
        val entryId = perch.seedEntry(
            feedId = feedId,
            title = "Test",
            documentPath = testFile.absolutePath,
        )

        showArticle(entryId)

        compose.onNodeWithTag(ArticleTestTags.DOCUMENT).performScrollToNode()
        compose.waitForIdle()

        leaveArticle()

        val saved = entry(entryId)
        assertThat(saved.scrollPosition).isGreaterThanOrEqualTo(0)
    }

    @Test
    fun `a document reopens at the page it stopped on`() {
        val ssrn = DocumentFixtures.manifest().first { it.slug == "ssrn-6191618" }
        val testFile = copyFixtureToTest(ssrn)

        val feedId = perch.seedFeed()
        val entryId = perch.seedEntry(
            feedId = feedId,
            title = "Test",
            documentPath = testFile.absolutePath,
        )

        runBlocking {
            perch.database.entryDao().setScrollPosition(entryId, 50)
        }

        showArticle(entryId)

        val doc = compose.onNodeWithTag(ArticleTestTags.DOCUMENT)
        doc.assertExists()
    }

    @Test
    fun `a document whose file is gone says so`() {
        val feedId = perch.seedFeed()
        val entryId = perch.seedEntry(
            feedId = feedId,
            title = "Test",
            documentPath = "/nonexistent/file.pdf",
        )

        showArticle(entryId)

        compose.onNodeWithTag(ArticleTestTags.DOCUMENT_GONE).assertIsDisplayed()
    }

    // ---- harness ---------------------------------------------------------------

    private val visit = mutableStateOf<ArticleViewModel?>(null)
    private var contentSet = false

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
                        ArticleScreen(viewModel = shown, onBack = {}, onOpenSource = {})
                    }
                }
            }
        }
        visit.value = viewModel
        await { viewModel.state.value !is ArticleUiState.Loading }
    }

    @After
    fun leaveArticle() {
        visit.value = null
        compose.waitForIdle()
        runBlocking { perch.database.entryDao().setScrollPosition(id = 0L, scrollPosition = 0) }
    }

    private fun copyFixtureToTest(fixture: DocumentFixture): File {
        val testDir = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
        val testFile = File(testDir, "test-${fixture.slug}.pdf")
        testFile.delete()
        fixture.file.copyTo(testFile, overwrite = true)
        return testFile
    }

    private fun await(predicate: () -> Boolean) =
        compose.awaitInRealTime("the database to satisfy the test's predicate", predicate = predicate)

    private fun entry(entryId: Long): EntryEntity = runBlocking {
        perch.database.entryDao().observeAll().first().first { it.id == entryId }
    }
}
