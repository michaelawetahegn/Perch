package dev.mkiros.perch.ui.collection

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.ui.theme.PerchTheme
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The save-link sheet's content (Y04, PLAN-6 §0.4): paste → save, over the real
 * [SaveLinkViewModel] and [dev.mkiros.perch.data.repo.SavedLinkRepository], so "a link
 * lands on To-Read" is asserted against a row on disk — the same shape
 * [dev.mkiros.perch.ui.source.AddSourceSheetTest] uses for the add-source sheet, and for
 * the same reason (NOTES.md): driven through the real content composable rather than the
 * bottom sheet it normally sits in, because an injected keystroke never reaches a node
 * inside a `ModalBottomSheet`.
 */
@RunWith(RobolectricTestRunner::class)
class SaveLinkSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: PerchDatabase
    private lateinit var server: MockWebServer
    private lateinit var container: AppContainer
    private lateinit var viewModel: SaveLinkViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = PerchDatabase.inMemory(context)
        server = MockWebServer()
        server.start()
        container = AppContainer(database = database, httpClient = PerchHttp.client(cacheDir = null))
    }

    @After
    fun tearDown() {
        server.shutdown()
        database.close()
    }

    @Test
    fun `pasting a link puts a titled row on To-Read`() {
        server.enqueue(article("A Pasted Article"))

        showSheet()
        paste(server.url("/post").toString())
        submit()
        awaitState { it.savedEntryId != null }

        val saved = runBlocking { database.entryDao().findById(viewModel.state.value.savedEntryId!!)!! }
        assertThat(saved.title).isEqualTo("A Pasted Article")
        assertThat(saved.isSaved).isTrue()
    }

    @Test
    fun `a bad link shows a reason and adds nothing`() {
        showSheet()
        // Nothing is listening here: the server is up, this port is not it.
        paste("http://127.0.0.1:1/post")
        submit()
        awaitState { it.error != null }

        compose.onNodeWithTag(SaveLinkTestTags.ERROR).assertIsDisplayed()
        assertThat(entryCount()).isEqualTo(0)
    }

    @Test
    fun `pasting a feed address says so rather than saving it as an article`() {
        server.enqueue(
            MockResponse()
                .setBody(
                    """
                    <?xml version="1.0"?>
                    <rss version="2.0"><channel><title>A Feed</title>
                    <item><guid>a1</guid><title>One</title></item>
                    </channel></rss>
                    """.trimIndent(),
                )
                .addHeader("Content-Type", "application/rss+xml"),
        )

        showSheet()
        paste(server.url("/feed.xml").toString())
        submit()
        awaitState { it.error != null }

        compose.onNodeWithText("That looks like a feed, not an article — add it as a source from the drawer instead.")
            .assertIsDisplayed()
        assertThat(entryCount()).isEqualTo(0)
    }

    // ---- harness ---------------------------------------------------------------

    private fun showSheet() {
        viewModel = SaveLinkViewModel(container.savedLinks)
        compose.setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            PerchTheme(dynamicColor = false) {
                SaveLinkSheetContent(
                    state = state,
                    onUrlChange = viewModel::onUrlChange,
                    onSubmit = viewModel::submit,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun paste(url: String) {
        compose.onNodeWithTag(SaveLinkTestTags.URL_FIELD).performTextInput(url)
        compose.waitForIdle()
    }

    private fun submit() {
        compose.onNodeWithTag(SaveLinkTestTags.SUBMIT)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun awaitState(predicate: (SaveLinkUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (predicate(viewModel.state.value)) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out; last state was ${viewModel.state.value}")
    }

    private fun entryCount(): Int = runBlocking { database.entryDao().countAll() }

    private fun article(title: String) = MockResponse()
        .setBody(
            """
            <html><head>
              <meta property="og:title" content="$title">
            </head><body><article><p>${"Real prose, with commas and length. ".repeat(30)}</p></article></body></html>
            """.trimIndent(),
        )
        .addHeader("Content-Type", "text/html; charset=utf-8")

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L
    }
}
