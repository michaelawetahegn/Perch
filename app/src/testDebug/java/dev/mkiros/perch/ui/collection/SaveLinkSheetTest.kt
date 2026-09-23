package dev.mkiros.perch.ui.collection

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.document.DocumentFixtures
import dev.mkiros.perch.model.Incoming
import dev.mkiros.perch.support.PerchRule
import dev.mkiros.perch.ui.nav.PerchNavHost
import dev.mkiros.perch.ui.screenshot.awaitInRealTime
import dev.mkiros.perch.ui.theme.PerchTheme
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
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

    private lateinit var server: MockWebServer
    private lateinit var viewModel: SaveLinkViewModel
    private lateinit var tempDir: java.io.File

    @get:Rule(order = 1)
    val perch = PerchRule(
        // Provide a DocumentOpener that can handle file:// URIs for testing
        documentOpener = object : dev.mkiros.perch.data.repo.DocumentOpener {
            override fun open(uri: Uri) = try {
                (pickedFiles[uri] ?: java.io.File(uri.path!!)).inputStream()
            } catch (e: Exception) {
                null
            }
        },
    )

    /** A picker's `content://` URIs, and the fixture each one stands for. */
    private val pickedFiles = mutableMapOf<Uri, java.io.File>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempDir = compose.activity.cacheDir
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `pasting a link puts a titled row on To-Read`() {
        server.enqueue(article("A Pasted Article"))

        showSheet()
        paste(server.url("/post").toString())
        submit()
        awaitState { it.savedEntryId != null }

        val saved = runBlocking { perch.database.entryDao().findById(viewModel.state.value.savedEntryId!!)!! }
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
    fun `a failure leaves its reason on screen with the address still there to fix`() {
        showSheet()
        val bad = "http://127.0.0.1:1/post"
        paste(bad)
        submit()
        awaitState { it.error != null }

        // S02/#33: "it should have errored out in the dialog before closing it" — so the
        // reason is readable, the address is still in the field, and the button is live
        // again for a second try. Nothing about this state closes the sheet.
        compose.onNodeWithTag(SaveLinkTestTags.ERROR).assertIsDisplayed()
        compose.onNodeWithTag(SaveLinkTestTags.SUBMIT).assertIsEnabled()
        assertThat(viewModel.state.value.url).isEqualTo(bad)
        assertThat(viewModel.state.value.canDismiss).isTrue()
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

    @Test
    fun `the sheet offers to choose a PDF`() {
        showSheet()
        compose.onNodeWithTag(SaveLinkTestTags.CHOOSE_FILE).assertIsDisplayed()
    }

    @Test
    fun `choosing a file imports it`() {
        showSheet()
        chooseFile()
        awaitState { it.savedEntryId != null }

        val saved = runBlocking { perch.database.entryDao().findById(viewModel.state.value.savedEntryId!!)!! }
        assertThat(saved.documentPath).isNotNull()
        assertThat(saved.title).contains("Letter")
        assertThat(saved.isSaved).isTrue()
    }

    @Test
    fun `cancelling the picker leaves the sheet as it was`() {
        showSheet()
        cancelFileChooser()

        compose.onNodeWithTag(SaveLinkTestTags.CHOOSE_FILE).assertIsDisplayed()
        assertThat(viewModel.state.value.isBusy).isFalse()
        assertThat(viewModel.state.value.error).isNull()
        assertThat(entryCount()).isEqualTo(0)
    }

    @Test
    fun `a picked file with no title takes its display name`() {
        // A real picker hands back a document id, never the file's name: the name is only
        // in the provider's DISPLAY_NAME column.
        val fixture = DocumentFixtures.manifest().first { it.slug == "empty-title" }
        val uri = Uri.parse("content://${PickedDocuments.AUTHORITY}/document/msf%3A42")
        pickedFiles[uri] = fixture.file
        PickedDocuments.displayName = "Quarterly_report.pdf"
        Robolectric.setupContentProvider(PickedDocuments::class.java, PickedDocuments.AUTHORITY)

        showSheet()
        chooseFile(uri)
        awaitState { it.savedEntryId != null }

        val saved = runBlocking { perch.database.entryDao().findById(viewModel.state.value.savedEntryId!!)!! }
        assertThat(saved.title).isEqualTo("Quarterly report")
    }

    @Test
    fun `a link shared to Perch lands on To-Read with the confirmation`() {
        server.enqueue(article("A Shared Article"))
        perch.container.intake.value = listOf(Incoming.Link(server.url("/shared").toString()))

        compose.setContent {
            PerchTheme(dynamicColor = false) {
                PerchNavHost(container = perch.container)
            }
        }

        compose.awaitInRealTime("the shared link saved") { entryCount() == 1 }
        compose.awaitInRealTime("the shared link's confirmation") {
            compose.onAllNodesWithText("Saved “A Shared Article”").fetchSemanticsNodes().isNotEmpty()
        }
        compose.awaitInRealTime("the shared link's row on To-Read") {
            compose.onAllNodesWithText("A Shared Article").fetchSemanticsNodes().isNotEmpty()
        }
        val saved = runBlocking { perch.database.entryDao().countAll() }
        assertThat(saved).isEqualTo(1)
        assertThat(perch.container.intake.value).isEmpty()
    }

    // ---- harness ---------------------------------------------------------------

    private lateinit var fakeRegistry: FakeActivityResultRegistry

    private fun showSheet() {
        fakeRegistry = FakeActivityResultRegistry()
        viewModel = SaveLinkViewModel(perch.container.savedLinks)
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = fakeRegistry
        }
        compose.setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
            ) {
                PerchTheme(dynamicColor = false) {
                    SaveLinkSheetContent(
                        state = state,
                        onUrlChange = viewModel::onUrlChange,
                        onSubmit = viewModel::submit,
                        onSubmitDocument = viewModel::submitDocument,
                    )
                }
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

    private fun chooseFile(uri: Uri = letterMarginsUri()) {
        compose.onNodeWithTag(SaveLinkTestTags.CHOOSE_FILE)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        // Simulate selecting a file
        fakeRegistry.simulateResult(uri)
        compose.waitForIdle()
        awaitState { it.isBusy || it.savedEntryId != null }
    }

    private fun cancelFileChooser() {
        compose.onNodeWithTag(SaveLinkTestTags.CHOOSE_FILE)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        // Simulate cancelling the picker
        fakeRegistry.simulateCancel()
        compose.waitForIdle()
    }

    private fun letterMarginsUri(): Uri {
        val fixture = DocumentFixtures.manifest().first { it.slug == "letter-margins" }
        val tempFile = java.io.File(tempDir, "letter-margins.pdf")
        fixture.file.copyTo(tempFile, overwrite = true)
        return Uri.fromFile(tempFile)
    }

    private fun awaitState(predicate: (SaveLinkUiState) -> Boolean) =
        compose.awaitInRealTime("a save-link state matching the test's predicate") {
            predicate(viewModel.state.value)
        }

    private fun entryCount(): Int = runBlocking { perch.database.entryDao().countAll() }

    private fun article(title: String) = MockResponse()
        .setBody(
            """
            <html><head>
              <meta property="og:title" content="$title">
            </head><body><article><p>${"Real prose, with commas and length. ".repeat(30)}</p></article></body></html>
            """.trimIndent(),
        )
        .addHeader("Content-Type", "text/html; charset=utf-8")

    /** The document provider a picker's URI belongs to: it answers DISPLAY_NAME and nothing else. */
    class PickedDocuments : ContentProvider() {
        override fun onCreate() = true
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(displayName)) }
        override fun getType(uri: Uri) = "application/pdf"
        override fun insert(uri: Uri, values: ContentValues?) = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

        companion object {
            const val AUTHORITY = "dev.mkiros.perch.test.documents"
            var displayName: String? = null
        }
    }

    /**
     * Fake ActivityResultRegistry for testing file picker without launching the real activity.
     * Simulates success or cancellation of ActivityResultContracts.OpenDocument().
     */
    private class FakeActivityResultRegistry : ActivityResultRegistry() {
        private var lastRequestCode: Int = -1

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            lastRequestCode = requestCode
        }

        fun simulateResult(uri: Uri) {
            if (lastRequestCode >= 0) {
                dispatchResult(lastRequestCode, uri)
            }
        }

        fun simulateCancel() {
            if (lastRequestCode >= 0) {
                dispatchResult(lastRequestCode, null)
            }
        }
    }
}
