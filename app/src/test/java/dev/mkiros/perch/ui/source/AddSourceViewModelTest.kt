package dev.mkiros.perch.ui.source

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.FeedDao
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.net.FeedFetcher
import dev.mkiros.perch.data.repo.FeedRepository
import dev.mkiros.perch.support.LaunchedJobs
import dev.mkiros.perch.support.PerchRule
import dev.mkiros.perch.support.awaitInRealTime
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * F10/#60 — the sheet's two `runCatching`s, and what they must not catch.
 *
 * Resolving and subscribing each fold every failure into an "unreachable" error, which is
 * right for a dead host and wrong for a cancellation: a job the caller stopped is not a
 * source that failed, and the coroutine has no business writing state after it. The seam is
 * D05's — the `CancellationException` comes up out of the fetch (or the write) while the
 * caller is alive to record it — and the claim is that the sheet's state is exactly what it
 * was when the action started. The sheet's happy paths are `AddSourceSheetTest`'s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AddSourceViewModelTest {

    @get:Rule
    val perch = PerchRule()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setHeader("Content-Type", "application/rss+xml").setBody(RSS)
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun `a resolve cancelled at the fetch is not reported as an unreachable source`() {
        val viewModel = viewModel(client = cancellingAtTheFetch())
        viewModel.onUrlChange(feedUrl())

        settle(viewModel) { viewModel.submit() }

        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.resolved).isNull()
        assertThat(viewModel.state.value.isBusy).isTrue()
    }

    @Test
    fun `a subscription cancelled at the write is not reported as an unreachable source`() {
        val viewModel = viewModel(feedDao = cancellingAtTheInsert(perch.database.feedDao()))
        viewModel.onUrlChange(feedUrl())
        viewModel.submit()
        awaitInRealTime("the source to resolve") { viewModel.state.value.resolved != null }

        settle(viewModel) { viewModel.submit() }

        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.addedFeedId).isNull()
        assertThat(viewModel.state.value.isBusy).isTrue()
    }

    // ---- harness ---------------------------------------------------------------

    private fun viewModel(
        feedDao: FeedDao = perch.database.feedDao(),
        client: OkHttpClient = OkHttpClient(),
    ) = AddSourceViewModel(
        feeds = FeedRepository(
            feedDao = feedDao,
            entryDao = perch.database.entryDao(),
            fetcher = FeedFetcher(client),
            clock = Clock.systemUTC(),
        ),
        folderRepository = perch.container.folders,
    )

    private fun feedUrl() = server.url("/feed.xml").toString()

    /** Runs [action] and waits out whatever it launched, however that ended. */
    private fun settle(viewModel: AddSourceViewModel, action: () -> Unit) {
        val launched = LaunchedJobs(viewModel.viewModelScope)
        action()
        runBlocking { launched.current.joinAll() }
    }

    private fun cancellingAtTheFetch() = OkHttpClient.Builder()
        .addInterceptor { throw CancellationException("the resolve was cancelled") }
        .build()

    private fun cancellingAtTheInsert(real: FeedDao): FeedDao = object : FeedDao by real {
        override suspend fun insert(feed: FeedEntity): Long =
            throw CancellationException("the subscription was cancelled")
    }

    private companion object {
        val RSS = """
            <?xml version="1.0"?>
            <rss version="2.0"><channel><title>Source</title>
            <item><title>One</title><link>https://source.example/one</link>
            <guid>https://source.example/one</guid></item>
            </channel></rss>
        """.trimIndent()
    }
}
