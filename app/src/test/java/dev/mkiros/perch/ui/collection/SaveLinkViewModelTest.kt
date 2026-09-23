package dev.mkiros.perch.ui.collection

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.document.DocumentFixtures
import dev.mkiros.perch.data.document.DocumentStore
import dev.mkiros.perch.data.net.FeedFetcher
import dev.mkiros.perch.data.repo.DocumentOpener
import dev.mkiros.perch.data.repo.SaveLinkFailure
import dev.mkiros.perch.data.repo.SavedLinkRepository
import dev.mkiros.perch.model.Incoming
import dev.mkiros.perch.support.FixtureRasterizer
import dev.mkiros.perch.support.awaitInRealTime
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S02/#33: the save-link sheet stays open until the link is in.
 *
 * The reader's complaint was that the sheet "just closes the dialog" — a scrim tap, a
 * swipe, or the sheet settling Hidden when the IME collapses on `ImeAction.Go` all reached
 * `onDismissRequest`, which called [SaveLinkViewModel.reset] unconditionally: the spinner
 * and any failure that arrived after it were wiped, and the stale error was what the *next*
 * opening showed.
 *
 * The rule that stops it is [SaveLinkUiState.canDismiss], consulted through
 * [SaveLinkViewModel.onDismissRequest], and it is asserted here rather than on the sheet
 * because an injected gesture never reaches a node inside a `ModalBottomSheet` (NOTES.md) —
 * the container only asks and obeys, so this is where the behaviour lives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SaveLinkViewModelTest {

    @get:Rule
    val tmpDir: TemporaryFolder = TemporaryFolder()

    private lateinit var database: PerchDatabase
    private lateinit var server: MockWebServer
    private lateinit var viewModel: SaveLinkViewModel

    /** What `MainActivity` offers when something is shared to Perch (PLAN-13 §0.8). */
    private val intake = MutableStateFlow<Incoming?>(null)

    /** The content resolver, as a map. */
    private val shared = mutableMapOf<Uri, File>()

    /** Held shut so a save can be observed mid-flight, and opened by the test that wants it. */
    private val gate = CountDownLatch(1)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database = PerchDatabase.inMemory(ApplicationProvider.getApplicationContext())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == READABLE_PATH) {
                    // A page that saves, so a test can reach what happens *after* the fetch.
                    MockResponse()
                        .setHeader("Content-Type", "text/html")
                        .setBody("<html><head><title>A post</title></head><body><p>Words.</p></body></html>")
                } else {
                    gate.await()
                    // Nothing there: the fetch fails, so this exercises the path the reader hit.
                    MockResponse().setResponseCode(404)
                }
        }
        server.start()
        val documents = DocumentStore(File(tmpDir.root, "documents"))
        viewModel = SaveLinkViewModel(
            SavedLinkRepository(
                feedDao = database.feedDao(),
                entryDao = database.entryDao(),
                fetcher = FeedFetcher(
                    OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
                ),
                clock = Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC),
                documents = documents,
                rasterizer = FixtureRasterizer(),
                documentOpener = DocumentOpener { uri -> shared[uri]?.inputStream() },
            ),
            intake,
        )
    }

    @After
    fun tearDown() {
        gate.countDown()
        server.shutdown()
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `an idle sheet can be dismissed, a working one cannot`() {
        assertThat(SaveLinkUiState().canDismiss).isTrue()
        assertThat(SaveLinkUiState(url = "https://example.com/post").canDismiss).isTrue()
        assertThat(SaveLinkUiState(isBusy = true).canDismiss).isFalse()
        assertThat(SaveLinkUiState(error = SaveLinkFailure.Unreachable("no")).canDismiss).isTrue()
        assertThat(SaveLinkUiState(savedEntryId = 7L).canDismiss).isTrue()
    }

    @Test
    fun `the sheet refuses to close while the link is still being fetched`() {
        viewModel.onUrlChange(server.url("/post").toString())
        viewModel.submit()

        assertThat(viewModel.state.value.isBusy).isTrue()
        assertThat(viewModel.state.value.canDismiss).isFalse()

        gate.countDown()
        awaitState { it.error != null }
        assertThat(viewModel.state.value.canDismiss).isTrue()
    }

    @Test
    fun `a failure arriving after the reader tried to dismiss is still readable`() {
        val url = server.url("/post").toString()
        viewModel.onUrlChange(url)
        viewModel.submit()

        // What a scrim tap, a swipe or the settling IME does to the sheet mid-flight.
        assertThat(viewModel.onDismissRequest()).isFalse()

        assertThat(viewModel.state.value.url).isEqualTo(url)
        assertThat(viewModel.state.value.isBusy).isTrue()

        gate.countDown()
        awaitState { it.error != null }
        assertThat(viewModel.state.value.error).isInstanceOf(SaveLinkFailure.Unreachable::class.java)
        assertThat(viewModel.state.value.url).isEqualTo(url)
    }

    @Test
    fun `dismissing a sheet that is doing nothing clears it for its next opening`() {
        viewModel.onUrlChange("https://example.com/post")

        assertThat(viewModel.onDismissRequest()).isTrue()
        assertThat(viewModel.state.value).isEqualTo(SaveLinkUiState())
    }

    /**
     * D06/#39: [SavedLinkRepository.saveLink] documents its failures as [SaveLinkFailure]
     * values, but it also has invariants it asserts with `error(...)` — and Room can raise
     * anything. Nothing caught them, so the throw left `viewModelScope` and took the
     * process with it; had it been survived, the sheet would have been left spinning on
     * `isBusy = true` forever, refusing every dismissal (`canDismiss`). Here the invariant
     * is broken for real, by removing the synthetic feed every saved link is filed on.
     */
    @Test
    fun `a repository that throws leaves a sheet the reader can read and close`() = runBlocking {
        val savedLinks = database.feedDao().findByUrl(FeedEntity.SAVED_LINKS_FEED_URL)!!
        database.feedDao().deleteById(savedLinks.id)

        viewModel.onUrlChange(server.url(READABLE_PATH).toString())
        viewModel.submit()

        awaitState { !it.isBusy }
        assertThat(viewModel.state.value.error).isNotNull()
        assertThat(viewModel.state.value.canDismiss).isTrue()
        assertThat(viewModel.onDismissRequest()).isTrue()
    }

    // ---- a share is a paste already confirmed (PLAN-13 §0.8) --------------------

    @Test
    fun `an incoming link opens the sheet and submits it`() {
        val url = server.url(READABLE_PATH).toString()

        intake.value = Incoming.Link(url)

        awaitState { it.savedEntryId != null }
        assertThat(viewModel.state.value.isOpen).isTrue()
        val row = runBlocking { database.entryDao().findById(viewModel.state.value.savedEntryId!!) }!!
        assertThat(row.link).isEqualTo(url)
        assertThat(row.isSaved).isTrue()
    }

    @Test
    fun `an incoming document opens the sheet and imports it`() {
        val uri = Uri.parse("content://test/letter-margins.pdf")
        shared[uri] = DocumentFixtures.manifest().first { it.slug == "letter-margins" }.file

        intake.value = Incoming.Document(uri, "letter-margins.pdf")

        awaitState { it.savedEntryId != null }
        assertThat(viewModel.state.value.isOpen).isTrue()
        val row = runBlocking { database.entryDao().findById(viewModel.state.value.savedEntryId!!) }!!
        assertThat(row.documentPath).isNotNull()
        assertThat(row.link).isNull()
    }

    @Test
    fun `the intake is cleared once taken`() {
        intake.value = Incoming.Link(server.url(READABLE_PATH).toString())

        awaitState { it.savedEntryId != null }
        assertThat(intake.value).isNull()
    }

    /** A second share while the first is still saving waits its turn; it is not dropped. */
    @Test
    fun `a share that arrives mid-save is taken once the save is done`() {
        viewModel.onUrlChange(server.url("/post").toString())
        viewModel.submit()
        assertThat(viewModel.state.value.isBusy).isTrue()

        val second = server.url(READABLE_PATH).toString()
        intake.value = Incoming.Link(second)
        assertThat(intake.value).isEqualTo(Incoming.Link(second))

        gate.countDown()
        awaitState { it.savedEntryId != null }
        val row = runBlocking { database.entryDao().findById(viewModel.state.value.savedEntryId!!) }!!
        assertThat(row.link).isEqualTo(second)
        assertThat(intake.value).isNull()
    }

    @Test
    fun `open and dismiss own the sheet's visibility`() {
        assertThat(viewModel.state.value.isOpen).isFalse()

        viewModel.open()
        assertThat(viewModel.state.value.isOpen).isTrue()

        assertThat(viewModel.onDismissRequest()).isTrue()
        assertThat(viewModel.state.value.isOpen).isFalse()
    }

    // ---- harness ---------------------------------------------------------------

    private fun awaitState(predicate: (SaveLinkUiState) -> Boolean) =
        awaitInRealTime("a save-link state matching the test's predicate") {
            predicate(viewModel.state.value)
        }

    private companion object {
        const val READABLE_PATH = "/article"
    }
}
