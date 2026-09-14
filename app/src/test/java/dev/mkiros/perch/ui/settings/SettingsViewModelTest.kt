package dev.mkiros.perch.ui.settings

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.net.FeedFetcher
import dev.mkiros.perch.data.repo.FeedRepository
import dev.mkiros.perch.support.AWAIT_TIMEOUT_MS
import dev.mkiros.perch.support.LaunchedJobs
import dev.mkiros.perch.support.PerchRule
import dev.mkiros.perch.support.awaitInRealTime
import java.io.IOException
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * The four transfers Settings offers (SPEC.md §9): OPML out, OPML in, profile out,
 * profile in.
 *
 * Each takes the document half as a lambda, because the `Uri` and the `ContentResolver`
 * belong to the screen — so each also inherits whatever that lambda throws. D06/#39: the
 * catch here used to be `IOException` alone, and a document URI the system has since
 * revoked raises `SecurityException`, which is not one. It escaped `viewModelScope`,
 * killed the process, and the reader never learned that their export had not happened.
 * The failure tests below name that exact exception; the happy paths beside them are the
 * pin that says widening the catch did not swallow a transfer that worked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var viewModel: SettingsViewModel

    /** The one address every source in this test lives at, so no refresh leaves the host. */
    private lateinit var feedUrl: String

    @get:Rule(order = 1)
    val perch = PerchRule()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setHeader("Content-Type", "application/rss+xml").setBody(RSS)
        }
        server.start()
        feedUrl = server.url("/feed.xml").toString()
        viewModel = SettingsViewModel(
            settings = perch.container.settings,
            opml = perch.container.opml,
            profile = perch.container.profile,
            feeds = perch.container.feeds,
            scheduler = {},
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    // ---- the transfers that work ------------------------------------------------

    @Test
    fun `exporting OPML hands the subscriptions to the writer and reports it`() {
        subscribe()
        var written: String? = null

        viewModel.exportOpml { written = it }

        awaitMessage { it is SettingsMessage.Exported }
        assertThat(written).contains(feedUrl)
    }

    @Test
    fun `importing OPML adds the source it names and reports how many landed`() {
        viewModel.importOpml { opmlNaming(feedUrl) }

        val message = awaitMessage { it is SettingsMessage.Imported }
        assertThat((message as SettingsMessage.Imported).added).isEqualTo(1)
        assertThat(runBlocking { perch.database.feedDao().findByUrl(feedUrl) }).isNotNull()
    }

    /**
     * F10/#60. The post-import refresh is silent by design, but it must not be silent about
     * a cancellation: a job the caller stopped unwinds, it does not finish as if it had run.
     * The seam is D05's — the `CancellationException` comes up out of the fetch — held at
     * the fetch until the refresh is the one job the import left running, so it can be
     * watched end.
     */
    @Test
    fun `the post-import refresh unwinds on a cancellation instead of finishing as if it had run`() {
        val reachedTheFetch = CountDownLatch(1)
        val release = CountDownLatch(1)
        viewModel = SettingsViewModel(
            settings = perch.container.settings,
            opml = perch.container.opml,
            profile = perch.container.profile,
            feeds = FeedRepository(
                feedDao = perch.database.feedDao(),
                entryDao = perch.database.entryDao(),
                fetcher = FeedFetcher(
                    OkHttpClient.Builder()
                        .addInterceptor {
                            reachedTheFetch.countDown()
                            release.await()
                            throw CancellationException("the refresh was cancelled")
                        }
                        .build(),
                ),
                clock = Clock.systemUTC(),
            ),
            scheduler = {},
        )

        try {
            val launched = LaunchedJobs(viewModel.viewModelScope)
            viewModel.importOpml { opmlNaming(feedUrl) }
            awaitMessage { it is SettingsMessage.Imported }
            assertThat(reachedTheFetch.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue()
            awaitInRealTime("the refresh to be the one job still running") {
                launched.current.count { it.isActive } == 1
            }
            val refresh = launched.current.single { it.isActive }

            release.countDown()
            runBlocking { refresh.join() }

            assertThat(refresh.isCancelled).isTrue()
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `exporting the profile hands the reading identity to the writer and reports it`() {
        subscribe()
        var written: String? = null

        viewModel.exportProfile { written = it }

        awaitMessage { it is SettingsMessage.ProfileExported }
        // The profile is JSON, and `org.json` escapes every `/`, so the address is asserted
        // by its distinctive tail rather than by the string that was inserted.
        assertThat(written).contains("feed.xml")
        assertThat(written).contains("\"title\": \"Source\"")
    }

    @Test
    fun `importing a profile restores it and reports what came back`() {
        subscribe()
        val exported = runBlocking { perch.container.profile.export() }

        viewModel.importProfile { exported }

        val message = awaitMessage { it is SettingsMessage.ProfileRestored }
        assertThat((message as SettingsMessage.ProfileRestored).sources).isEqualTo(0)
    }

    // ---- the transfers that throw something that is not an IOException -----------

    @Test
    fun `a revoked document is a message, not a dead process, on OPML export`() {
        viewModel.exportOpml { throw SecurityException("Permission Denial: $feedUrl") }

        assertThat(awaitMessage()).isEqualTo(SettingsMessage.TransferFailed)
    }

    @Test
    fun `a revoked document is a message, not a dead process, on OPML import`() {
        viewModel.importOpml { throw SecurityException("Permission Denial: $feedUrl") }

        assertThat(awaitMessage()).isEqualTo(SettingsMessage.TransferFailed)
    }

    @Test
    fun `a revoked document is a message, not a dead process, on profile export`() {
        viewModel.exportProfile { throw SecurityException("Permission Denial: $feedUrl") }

        assertThat(awaitMessage()).isEqualTo(SettingsMessage.TransferFailed)
    }

    @Test
    fun `a revoked document is a message, not a dead process, on profile import`() {
        viewModel.importProfile { throw SecurityException("Permission Denial: $feedUrl") }

        assertThat(awaitMessage()).isEqualTo(SettingsMessage.TransferFailed)
    }

    /** The narrower reason still reads the same way; widening the catch kept it. */
    @Test
    fun `an unreadable document is still reported as a failed transfer`() {
        viewModel.importOpml { throw IOException("no such file") }

        assertThat(awaitMessage()).isEqualTo(SettingsMessage.TransferFailed)
    }

    // ---- harness ----------------------------------------------------------------

    private fun subscribe() = runBlocking {
        perch.database.feedDao().insert(
            FeedEntity(
                feedUrl = feedUrl,
                siteUrl = server.url("/").toString(),
                title = "Source",
                customTitle = null,
                faviconUrl = null,
                etag = null,
                lastModified = null,
                lastFetchedAt = null,
                lastSuccessAt = null,
                lastError = null,
                addedAt = 0L,
            ),
        )
    }

    private fun opmlNaming(url: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <opml version="2.0">
          <head><title>Perch</title></head>
          <body><outline type="rss" text="Source" title="Source" xmlUrl="$url"/></body>
        </opml>
    """.trimIndent()

    private fun awaitMessage(
        predicate: (SettingsMessage) -> Boolean = { true },
    ): SettingsMessage {
        var matched: SettingsMessage? = null
        awaitInRealTime("a message matching the test's predicate") {
            matched = viewModel.message.value?.takeIf(predicate)
            matched != null
        }
        return matched!!
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
