package dev.mkiros.perch.ui.source

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.data.repo.SourceResolution
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.ui.theme.PerchTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The add-source sheet (T23): paste → resolve → confirm-then-commit (DESIGN.md §5).
 *
 * The sheet is driven by the real [AddSourceViewModel] over the real [dev.mkiros.perch
 * .data.repo.FeedRepository], an in-memory database and a `MockWebServer` — so "adds a
 * source" is asserted against rows on disk, not against a fake that was told to succeed.
 *
 * Resolving hops to `Dispatchers.IO` and Room writes on its own executor, so every
 * assertion about a *later* state waits in wall-clock time; see
 * [dev.mkiros.perch.ui.home.HomeScreenTest] for why a virtual-clock spin cannot work.
 */
@RunWith(RobolectricTestRunner::class)
class AddSourceSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: PerchDatabase
    private lateinit var server: MockWebServer
    private lateinit var container: AppContainer
    private lateinit var viewModel: AddSourceViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PerchDatabase::class.java).build()
        server = MockWebServer()
        server.start()
        container = AppContainer(
            database = database,
            httpClient = PerchHttp.client(cacheDir = null),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        database.close()
    }

    @Test
    fun `a pasted feed address is confirmed before anything is subscribed to`() {
        server.dispatcher = serving("/feed.xml" to feed(items = 2))

        showSheet()
        paste(server.url("/feed.xml").toString())
        submit()
        awaitState { it.resolved != null }

        // The feed's own title and how much it carries — the confirmation the reader
        // acts on. Nothing is subscribed to yet.
        compose.onNodeWithText("Example Feed").assertIsDisplayed()
        compose.onNodeWithText("2 entries").assertIsDisplayed()
        assertThat(feedCount()).isEqualTo(0)
    }

    @Test
    fun `confirming a resolved feed adds it with the entries already fetched`() {
        server.dispatcher = serving("/feed.xml" to feed(items = 2))

        showSheet()
        paste(server.url("/feed.xml").toString())
        submit()
        awaitState { it.resolved != null }
        submit()
        awaitState { it.addedFeedId != null }

        assertThat(feedUrls()).containsExactly(server.url("/feed.xml").toString())
        assertThat(entryCount()).isEqualTo(2)
    }

    @Test
    fun `a pasted homepage is discovered and then added`() {
        server.dispatcher = serving(
            "/" to html("""<link rel="alternate" type="application/rss+xml" href="/feed.xml">"""),
            "/feed.xml" to feed(items = 1),
        )

        showSheet()
        paste(server.url("/").toString())
        submit()
        awaitState { it.resolved != null }
        submit()
        awaitState { it.addedFeedId != null }

        assertThat(feedUrls()).containsExactly(server.url("/feed.xml").toString())
        assertThat(entryCount()).isEqualTo(1)
    }

    @Test
    fun `an address that leads to no feed shows an inline error and adds nothing`() {
        // Reachable, but neither a feed nor a page declaring one — and every path guess
        // soft-404s to HTML, the shape T11's negative case describes.
        server.dispatcher = serving()

        showSheet()
        paste(server.url("/").toString())
        submit()
        awaitState { it.error != null }

        compose.onNodeWithTag(AddSourceTestTags.ERROR).assertIsDisplayed()
        compose.onNodeWithText("No feed found at that address").assertIsDisplayed()
        assertThat(feedCount()).isEqualTo(0)
    }

    @Test
    fun `an unreachable address says so and leaves the address editable`() {
        showSheet()
        // Nothing is listening here: the server is up, this port is not it.
        paste("http://127.0.0.1:1/feed.xml")
        submit()
        awaitState { it.error != null }

        compose.onNodeWithTag(AddSourceTestTags.ERROR).assertIsDisplayed()
        paste("https://example.com/feed.xml")
        assertThat(viewModel.state.value.error).isNull()
        assertThat(feedCount()).isEqualTo(0)
    }

    @Test
    fun `an address already subscribed to is named rather than added twice`() {
        server.dispatcher = serving("/feed.xml" to feed(items = 1))
        val url = server.url("/feed.xml").toString()
        runBlocking {
            container.feeds.add(container.feeds.resolve(url) as SourceResolution.Resolved)
        }

        showSheet()
        paste(url)
        submit()
        awaitState { it.error != null }

        compose.onNodeWithText("You already follow Example Feed").assertIsDisplayed()
        assertThat(feedCount()).isEqualTo(1)
    }

    @Test
    fun `editing the address after resolving withdraws the confirmation`() {
        server.dispatcher = serving("/feed.xml" to feed(items = 2))

        showSheet()
        paste(server.url("/feed.xml").toString())
        submit()
        awaitState { it.resolved != null }
        paste(server.url("/other.xml").toString())

        // Committing what was on screen a keystroke ago would subscribe to the wrong
        // address, so the resolution goes with the edit.
        assertThat(viewModel.state.value.resolved).isNull()
        compose.onNodeWithText("2 entries").assertDoesNotExist()
    }

    @Test
    fun `an empty field cannot be submitted`() {
        showSheet()

        compose.onNodeWithTag(AddSourceTestTags.SUBMIT).assertIsNotEnabled()
    }

    // ---- harness ---------------------------------------------------------------

    private fun showSheet() {
        viewModel = AddSourceViewModel(container.feeds)
        compose.setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            PerchTheme(dynamicColor = false) {
                AddSourceSheetContent(
                    state = state,
                    onUrlChange = viewModel::onUrlChange,
                    onSubmit = viewModel::submit,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun paste(url: String) {
        val field = compose.onNodeWithTag(AddSourceTestTags.URL_FIELD)
        if (viewModel.state.value.url.isEmpty()) field.performTextInput(url)
        else field.performTextReplacement(url)
        compose.waitForIdle()
    }

    /** The primary button, driven through its own semantics rather than a synthetic tap. */
    private fun submit() {
        compose.onNodeWithTag(AddSourceTestTags.SUBMIT)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun awaitState(predicate: (AddSourceUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (predicate(viewModel.state.value)) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out; last state was ${viewModel.state.value}")
    }

    private fun feedUrls(): List<String> = runBlocking {
        database.feedDao().observeAll().first().map { it.feedUrl }
    }

    private fun feedCount(): Int = feedUrls().size

    private fun entryCount(): Int = runBlocking { database.entryDao().countAll() }

    /** Serves [overrides] by path; everything else soft-404s to a plain HTML page. */
    private fun serving(vararg overrides: Pair<String, MockResponse>) =
        object : Dispatcher() {
            private val byPath = overrides.toMap()
            override fun dispatch(request: RecordedRequest): MockResponse =
                byPath[request.path] ?: html("<p>Nothing here.</p>")
        }

    private fun html(body: String) = MockResponse()
        .setBody("<!doctype html><html><head>$body</head><body>$body</body></html>")
        .addHeader("Content-Type", "text/html; charset=utf-8")

    private fun feed(items: Int) = MockResponse()
        .setBody(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <rss version="2.0"><channel>
              <title>Example Feed</title>
              <link>https://example.com/</link>
              ${(1..items).joinToString("\n") { rssItem(it) }}
            </channel></rss>
            """.trimIndent(),
        )
        .addHeader("Content-Type", "application/rss+xml; charset=utf-8")

    private fun rssItem(n: Int) =
        "<item><guid isPermaLink=\"false\">e$n</guid><title>Entry $n</title>" +
            "<link>https://example.com/e$n</link>" +
            "<pubDate>Mon, 0$n Aug 2026 10:00:00 GMT</pubDate></item>"

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L
    }
}
