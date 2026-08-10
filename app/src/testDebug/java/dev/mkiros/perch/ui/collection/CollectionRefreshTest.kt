package dev.mkiros.perch.ui.collection

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.ui.screenshot.awaitInRealTime
import dev.mkiros.perch.ui.theme.PerchTheme
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
import org.robolectric.annotation.Config

/**
 * V03/#6 on the reader's own two lists: an empty To-Read or Liked answers the pull.
 *
 * These lists were built from the Feed's pattern (U09) but never grew its pull surface, so
 * the gesture had nowhere to land at all — empty or full. Asserted at the wire, like
 * [dev.mkiros.perch.ui.home.HomeRefreshTest]: "the pull refreshes" is only worth asserting
 * if what is counted is a fetch that happened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class CollectionRefreshTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: PerchDatabase
    private lateinit var container: AppContainer
    private lateinit var viewModel: CollectionViewModel
    private lateinit var server: MockWebServer

    private val clock = Clock.fixed(Instant.parse("2026-08-07T12:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setHeader("Content-Type", "application/rss+xml")
                .setBody(RSS)
        }
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = PerchDatabase.inMemory(context)
        container = AppContainer(
            database = database,
            httpClient = PerchHttp.client(cacheDir = null),
            clock = clock,
        )
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    @Test
    fun `pulling an empty To-Read refreshes every source`() {
        seedFeed("/one.xml")

        showEmpty(Collection.ToRead)
        pullToRefresh()

        assertThat(requestedPaths()).containsExactly("/one.xml")
    }

    @Test
    fun `pulling an empty Liked refreshes every source`() {
        seedFeed("/one.xml")

        showEmpty(Collection.Liked)
        pullToRefresh()

        assertThat(requestedPaths()).containsExactly("/one.xml")
    }

    // ---- harness ---------------------------------------------------------------------

    private fun pullToRefresh() {
        compose.onNodeWithTag(CollectionTestTags.LIST).performTouchInput { swipeDown() }
        compose.awaitInRealTime("the refresh to finish") {
            !viewModel.isRefreshing.value && server.requestCount > 0
        }
        compose.waitForIdle()
    }

    private fun requestedPaths(): List<String> =
        List(server.requestCount) { server.takeRequest().path.orEmpty() }

    private fun showEmpty(collection: Collection) {
        viewModel = CollectionViewModel(container.entries, container.feeds, clock, collection)
        compose.setContent {
            PerchTheme(dynamicColor = false) {
                CollectionScreen(viewModel = viewModel, onOpenEntry = {})
            }
        }
        compose.awaitInRealTime("the empty state") {
            compose.onAllNodesWithTag(CollectionTestTags.EMPTY).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun seedFeed(path: String): Long = runBlocking {
        database.feedDao().insert(
            FeedEntity(
                feedUrl = server.url(path).toString(),
                siteUrl = "https://example.com",
                title = "Source One",
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

    private companion object {
        val RSS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Source One</title>
                <link>https://example.com</link>
                <item>
                  <title>Fresh from one</title>
                  <link>https://example.com/fresh</link>
                  <guid>https://example.com/fresh</guid>
                  <pubDate>Thu, 06 Aug 2026 09:00:00 GMT</pubDate>
                  <description>A short summary.</description>
                </item>
              </channel>
            </rss>
        """.trimIndent()
    }
}
