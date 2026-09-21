package dev.mkiros.perch.ui.screenshot

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.document.DocumentFixtures
import dev.mkiros.perch.data.repo.ArticleTextRepository
import dev.mkiros.perch.debug.DebugSeeder
import dev.mkiros.perch.model.ThemeMode
import dev.mkiros.perch.model.TimeFilter
import dev.mkiros.perch.support.PerchRule
import dev.mkiros.perch.ui.article.ArticleScreen
import dev.mkiros.perch.ui.article.ArticleTestTags
import dev.mkiros.perch.ui.article.ArticleViewModel
import dev.mkiros.perch.ui.collection.CollectionTestTags
import dev.mkiros.perch.ui.nav.NavTestTags
import dev.mkiros.perch.ui.nav.PerchNavHost
import dev.mkiros.perch.ui.nav.PerchTab
import dev.mkiros.perch.ui.theme.PerchTheme
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The document gallery (PLAN-13 §0.9): the To-Read row — first page as its thumbnail, labelled
 * a PDF — and the reader, in both themes, over the `ssrn-6191618` fixture.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class DocumentScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val now = Instant.parse("2026-08-07T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private lateinit var server: MockWebServer

    private val settings = dev.mkiros.perch.data.settings.SettingsStore.inMemory().also {
        runBlocking { it.setTimeFilter(TimeFilter.AllTime) }
    }

    @get:Rule(order = 1)
    val perch = PerchRule(clock = clock)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    /** Whether the reader is composed; dropping it is what writes the page it stopped on. */
    private val readerShown = mutableStateOf(true)

    @After
    fun tearDown() {
        // Drain the reader's NonCancellable leaving write (NOTES.md, E01) before PerchRule closes.
        readerShown.value = false
        compose.waitForIdle()
        runBlocking { perch.database.entryDao().setScrollPosition(id = 0L, scrollPosition = 0) }
        Coil.reset()
        server.shutdown()
    }

    @Test
    fun `a document row in To-Read beside a pasted link`() {
        // Seed a pasted link first (this creates the saved-links feed)
        server.enqueue(
            MockResponse()
                .setBody(
                    """
                    <html><head>
                      <meta property="og:title" content="The Mean Means Nothing">
                    </head><body><article><p>${"Real prose, with commas and length. ".repeat(30)}</p></article></body></html>
                    """.trimIndent(),
                )
                .addHeader("Content-Type", "text/html; charset=utf-8"),
        )
        runBlocking {
            perch.container.savedLinks.saveLink(server.url("/the-mean-means-nothing").toString())
                .getOrThrow()
        }

        // Now that saved-links feed exists, seed a document entry
        val savedLinksFeedId = runBlocking {
            perch.database.feedDao().findByUrl(FeedEntity.SAVED_LINKS_FEED_URL)!!.id
        }

        val renderedPng = File(repoRoot(), "fixtures/documents/rendered/ssrn-6191618-1.png")
        val imageUrl = renderedPng.toURI().toString()

        perch.seedEntry(
            feedId = savedLinksFeedId,
            title = "What We Know About the SSRN Paper",
            guid = "perch:document:ssrn-sample",
            link = null,
            documentPath = "documents/ssrn-6191618.pdf",
            imageUrl = imageUrl,
            savedAt = now.toEpochMilli(),
            publishedAt = Instant.parse("2026-06-30T03:07:59Z").toEpochMilli(),
            publishedIsEstimated = false,
        )

        // Show the shell and navigate to To-Read
        showShell(ThemeMode.Dark)
        compose.onNodeWithTag(NavTestTags.tab(PerchTab.ToRead)).performClick()
        compose.awaitInRealTime("the queue to load") {
            compose.onAllNodesWithTag(CollectionTestTags.ENTRY).fetchSemanticsNodes().isNotEmpty()
        }

        Screenshots.captureAndAssert(compose, "document-to-read-row", minBytes = 10_000L)
    }

    @Test
    fun `the document reader in light`() {
        showReader(ThemeMode.Light)

        Screenshots.captureAndAssert(compose, "document-reader-light", minBytes = 10_000L)
    }

    @Test
    fun `the document reader in dark`() {
        showReader(ThemeMode.Dark)

        Screenshots.captureAndAssert(compose, "document-reader-dark", minBytes = 10_000L)
    }

    @Test
    fun `the document reader scrolled to page two, the toast showing`() {
        showReader(ThemeMode.Light)
        // Item N is page N: page two at the top of the viewport is under its centre.
        compose.onNodeWithTag(ArticleTestTags.DOCUMENT).performScrollToIndex(2)
        compose.awaitInRealTime("page two to render") {
            compose.onAllNodesWithTag("${ArticleTestTags.DOCUMENT_PAGE_IMAGE}:1", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(ArticleTestTags.DOCUMENT_TOAST, useUnmergedTree = true).assertExists()

        Screenshots.captureAndAssert(compose, "document-reader-scrolled", minBytes = 10_000L)
    }

    /** The reader's own sample (#72), opened from To-Read the way a pasted PDF lands. */
    private fun showReader(mode: ThemeMode) {
        val fixture = DocumentFixtures.manifest().first { it.slug == "ssrn-6191618" }
        val stored = perch.container.documents.newDocument()
        fixture.file.copyTo(stored, overwrite = true)
        val id = perch.seedEntry(
            runBlocking { perch.database.feedDao().findByUrl(FeedEntity.SAVED_LINKS_FEED_URL)!!.id },
            title = fixture.title!!,
            guid = "perch:document:ssrn-6191618",
            link = null,
            author = "Joshua Della Vedova",
            publishedAt = fixture.published!!.toEpochMilli(),
            contentHtml = null,
            documentPath = perch.container.documents.relativize(stored),
            savedAt = now.toEpochMilli(),
        )
        val viewModel = ArticleViewModel(
            entries = perch.container.entries,
            feeds = perch.container.feeds,
            articleText = ArticleTextRepository(perch.database.entryDao(), { null }, clock),
            entryId = id,
            zone = ZoneOffset.UTC,
            rasterizer = perch.container.rasterizer,
            documents = perch.container.documents,
        )
        compose.setContent {
            PerchTheme(mode = mode, dynamicColor = false) {
                if (readerShown.value) ArticleScreen(viewModel = viewModel, onBack = {})
            }
        }
        compose.awaitInRealTime("the first two pages to render") {
            listOf(0, 1).all { page ->
                compose.onAllNodesWithTag("${ArticleTestTags.DOCUMENT_PAGE_IMAGE}:$page", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    private fun showShell(mode: ThemeMode) {
        compose.setContent {
            PerchTheme(mode = mode, dynamicColor = false) {
                PerchNavHost(container = perch.container)
            }
        }
        compose.waitForIdle()
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("settings.gradle.kts not found above ${File("").absolutePath}")
    }
}
