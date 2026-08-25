package dev.mkiros.perch.ui.home

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.db.entity.FolderEntity
import dev.mkiros.perch.data.net.ConnectivityMonitor
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.data.settings.SettingsStore
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.ui.source.AddSourceViewModel
import dev.mkiros.perch.ui.theme.PerchTheme
import dev.mkiros.perch.ui.rowTitles
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
 * T26: what home does about refreshing, and about the two ways the world can be wrong.
 *
 * The refresh assertions go all the way to the wire — a real [MockWebServer] behind the
 * real `FeedRepository` — because "pull-to-refresh calls the repository once" is only
 * worth asserting if the thing being counted is a fetch that actually happened. A mock
 * repository would prove the lambda was invoked and nothing else.
 *
 * The error assertions are the inverse: nothing is fetched, `lastError` is seeded
 * directly, and what is under test is that a failing source **does not** cost the reader
 * the entries it fetched before it broke (DESIGN.md §7).
 */
@RunWith(RobolectricTestRunner::class)
class HomeRefreshTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: PerchDatabase
    private lateinit var container: AppContainer
    private lateinit var viewModel: HomeViewModel
    private lateinit var server: MockWebServer

    private val now = Instant.parse("2026-08-07T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    /** Feed bodies by path. A dispatcher, not a queue: two sources refresh concurrently. */
    private val bodies = mutableMapOf<String, String>()

    /**
     * These tests are about the list, the drawer and the row — not about U07's window,
     * which [dev.mkiros.perch.ui.home.HomeTimeFilterTest] owns. Home opens on Today, so
     * without this every entry seeded a day or two back would be filtered out and the
     * assertions would be about an empty screen.
     */
    private val settings = SettingsStore.inMemory().also {
        runBlocking { it.setTimeFilter(TimeFilter.AllTime) }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = bodies[request.path] ?: return MockResponse().setResponseCode(404)
                return MockResponse()
                    .setHeader("Content-Type", "application/rss+xml")
                    .setBody(body)
            }
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

    // ---- refreshing ---------------------------------------------------------------

    @Test
    fun `pulling the list refreshes every source exactly once`() {
        val one = seedRemoteFeed(path = "/one.xml", title = "Source One", entryTitle = "Fresh from one")
        val two = seedRemoteFeed(path = "/two.xml", title = "Source Two", entryTitle = "Fresh from two")
        seedEntry(feedId = one, title = "Cached in one")
        seedEntry(feedId = two, title = "Cached in two")

        showHome()
        pullToRefresh()

        assertThat(requestedPaths()).containsExactly("/one.xml", "/two.xml")
        // The write lands before `refreshAll` returns, but the Room flow that carries it
        // to the list does not, so wait for the emission rather than for the gesture.
        awaitCondition { listedTitles().containsAll(FRESH) }

        // Asserted on the list's *query* rather than on its rows: `LazyColumn` anchors on
        // the key that was first before the write, so entries prepended by a refresh are
        // composed above the viewport until the reader scrolls up — and since U07a they
        // are not even loaded until then. What a row looks like is T21's test.
        assertThat(listedTitles()).containsAtLeast("Fresh from one", "Fresh from two")
        assertThat(listedTitles()).hasSize(4)
    }

    @Test
    fun `refreshing while filtered polls only the source on screen`() {
        val one = seedRemoteFeed(path = "/one.xml", title = "Source One", entryTitle = "Fresh from one")
        val two = seedRemoteFeed(path = "/two.xml", title = "Source Two", entryTitle = "Fresh from two")
        seedEntry(feedId = one, title = "Cached in one")
        seedEntry(feedId = two, title = "Cached in two")

        showHome()
        selectInDrawer("Source Two")
        pullToRefresh()

        assertThat(requestedPaths()).containsExactly("/two.xml")
    }

    /**
     * V03/#6: the gesture a reader reaches for on a fresh install.
     *
     * `PullToRefreshBox` only sees a drag its child dispatches, so an empty state that is
     * not scrollable swallows the one gesture that would fill it. Asserted at the wire for
     * the same reason the full-list case is: what matters is that the pull reached a fetch.
     */
    @Test
    fun `pulling an empty list still refreshes every source`() {
        seedRemoteFeed(path = "/one.xml", title = "Source One", entryTitle = "Fresh from one")

        showHome()
        awaitCondition { compose.onAllNodesWithText(ALL_CAUGHT_UP).fetchSemanticsNodes().isNotEmpty() }
        pullToRefresh()

        assertThat(requestedPaths()).containsExactly("/one.xml")
    }

    @Test
    fun `the indicator comes back down when the refresh is done`() {
        val one = seedRemoteFeed(path = "/one.xml", title = "Source One", entryTitle = "Fresh from one")
        seedEntry(feedId = one, title = "Cached in one")

        showHome()
        pullToRefresh()

        assertThat(viewModel.isRefreshing.value).isFalse()
    }

    // ---- a source that is failing (DESIGN.md §7) ------------------------------------

    @Test
    fun `a failing source keeps its cached entries listed and is flagged in the drawer`() {
        val broken = seedFeed(title = "Broken", lastError = "Connection reset")
        seedEntry(feedId = broken, title = "Fetched before it broke")

        showHome()

        compose.onNodeWithText("Fetched before it broke").assertIsDisplayed()
        expandInDrawer(FolderEntity.UNCATEGORIZED_ID)
        compose.onNodeWithContentDescription("Not updating").assertIsDisplayed()
    }

    @Test
    fun `filtering to a failing source shows its message with a retry`() {
        val broken = seedFeed(title = "Broken", lastError = "Connection reset")
        seedEntry(feedId = broken, title = "Fetched before it broke")

        showHome()
        selectInDrawer("Broken")

        compose.onNodeWithText("Connection reset").assertIsDisplayed()
        compose.onNodeWithTag(HomeTestTags.BANNER_RETRY).assertIsDisplayed()
        // The banner sits above the list rather than in place of it.
        compose.onNodeWithText("Fetched before it broke").assertIsDisplayed()
    }

    @Test
    fun `retrying from the banner re-polls that source`() {
        val broken = seedRemoteFeed(
            path = "/broken.xml",
            title = "Broken",
            entryTitle = "Back from the dead",
            lastError = "Connection reset",
        )
        seedEntry(feedId = broken, title = "Fetched before it broke")

        showHome()
        selectInDrawer("Broken")
        compose.onNodeWithTag(HomeTestTags.BANNER_RETRY)
            .performSemanticsAction(SemanticsActions.OnClick)
        awaitCondition { compose.rowTitles().contains("Back from the dead") }

        assertThat(requestedPaths()).containsExactly("/broken.xml")
    }

    @Test
    fun `with every source failing the banner is global and dismissible`() {
        val one = seedFeed(title = "Source One", lastError = "Connection reset")
        seedFeed(title = "Source Two", lastError = "Host unreachable")
        seedEntry(feedId = one, title = "Still readable")

        showHome()

        compose.onNodeWithText("No source could be refreshed").assertIsDisplayed()
        compose.onNodeWithText("Still readable").assertIsDisplayed()

        compose.onNodeWithTag(HomeTestTags.BANNER_DISMISS)
            .performSemanticsAction(SemanticsActions.OnClick)
        awaitState { it.banner == null }
        compose.onNodeWithTag(HomeTestTags.BANNER).assertDoesNotExist()
    }

    @Test
    fun `one healthy source is enough to keep the global banner away`() {
        val healthy = seedFeed(title = "Healthy")
        seedFeed(title = "Broken", lastError = "Connection reset")
        seedEntry(feedId = healthy, title = "Something to read")

        showHome()

        compose.onNodeWithTag(HomeTestTags.BANNER).assertDoesNotExist()
    }

    // ---- offline (DESIGN.md §7) -----------------------------------------------------

    @Test
    fun `offline says so above the list and leaves the list readable`() {
        val feedId = seedFeed(title = "Source One")
        seedEntry(feedId = feedId, title = "Saved earlier")

        showHome(connectivity = ConnectivityMonitor.AlwaysOffline)

        compose.onNodeWithText("Offline — showing saved entries").assertIsDisplayed()
        compose.onNodeWithText("Saved earlier").assertIsDisplayed()
        // Nothing to retry until the network is back, so the strip offers no button.
        compose.onNodeWithTag(HomeTestTags.BANNER_RETRY).assertDoesNotExist()
    }

    @Test
    fun `offline outranks a failing source, which is failing because of it`() {
        val feedId = seedFeed(title = "Source One", lastError = "Host unreachable")
        seedEntry(feedId = feedId, title = "Saved earlier")

        showHome(connectivity = ConnectivityMonitor.AlwaysOffline)

        compose.onNodeWithText("Offline — showing saved entries").assertIsDisplayed()
        compose.onNodeWithText("No source could be refreshed").assertDoesNotExist()
    }

    // ---- mark all read, and taking it back ------------------------------------------

    @Test
    fun `mark all read empties the list and offers to undo`() {
        val feedId = seedFeed(title = "Source One")
        seedEntry(feedId = feedId, title = "First")
        seedEntry(feedId = feedId, title = "Second")

        showHome()
        markAllRead()

        compose.onNodeWithText("2 entries marked read").assertIsDisplayed()
        compose.onNodeWithText("You're all caught up").assertIsDisplayed()
    }

    @Test
    fun `undo restores exactly the entries that batch marked, and no others`() {
        val feedId = seedFeed(title = "Source One")
        seedEntry(feedId = feedId, title = "First")
        seedEntry(feedId = feedId, title = "Second")
        seedEntry(feedId = feedId, title = "Read last week", readAt = now.toEpochMilli())
        val unreadBefore = unreadTitles()

        showHome()
        markAllRead()
        assertThat(unreadTitles()).isEmpty()

        compose.onNodeWithText("Undo").performSemanticsAction(SemanticsActions.OnClick)
        awaitCondition { compose.rowTitles().size == unreadBefore.size }

        // The entry that was already read stays read: undo is scoped to what it flipped.
        assertThat(unreadTitles()).containsExactlyElementsIn(unreadBefore)
        assertThat(unreadBefore).containsExactly("First", "Second")
    }

    @Test
    fun `mark all read is scoped to the source being filtered on`() {
        val one = seedFeed(title = "Source One")
        val two = seedFeed(title = "Source Two")
        seedEntry(feedId = one, title = "Only in one")
        seedEntry(feedId = two, title = "Only in two")

        showHome()
        selectInDrawer("Source One")
        markAllRead()

        assertThat(unreadTitles()).containsExactly("Only in two")
    }

    // ---- harness ---------------------------------------------------------------------

    /**
     * The gesture itself, on the pull surface. This is deliberately not a direct call to
     * `viewModel.refresh()`: what the Done-condition is about is that the gesture reaches
     * the repository, and exactly once.
     */
    private fun pullToRefresh() {
        compose.onNodeWithTag(HomeTestTags.LIST).performTouchInput { swipeDown() }
        awaitCondition { !viewModel.isRefreshing.value && server.requestCount > 0 }
        compose.waitForIdle()
    }

    private fun markAllRead() {
        compose.onNodeWithContentDescription("More options").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(HomeTestTags.MARK_ALL_READ)
            .performSemanticsAction(SemanticsActions.OnClick)
        awaitCondition { compose.rowTitles().isEmpty() }
    }

    private fun openDrawer() {
        compose.onNodeWithContentDescription("Open sources").performClick()
        compose.waitForIdle()
    }

    /** Matched by click action: the app bar can carry the same text as the drawer row. */
    private fun selectInDrawer(label: String, folderId: Long = FolderEntity.UNCATEGORIZED_ID) {
        expandInDrawer(folderId)
        compose.onAllNodesWithText(label)
            .filterToOne(hasClickAction() and !hasTestTag(HomeTestTags.ENTRY))
            .performSemanticsAction(SemanticsActions.OnClick)
        awaitState { it.selectedTitle == label }
    }

    /**
     * Opens the drawer and, if [folderId]'s section is not already open, expands it —
     * §0.1 means a source row does not exist in the tree until its folder is. Checked
     * against the ViewModel rather than clicked unconditionally: a second click would
     * collapse it again (`toggleFolderExpanded`).
     */
    private fun expandInDrawer(folderId: Long) {
        openDrawer()
        if (folderId !in viewModel.expandedFolders.value) {
            compose.onNodeWithTag(HomeTestTags.folderExpand(folderId))
                .performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
        }
    }

    private fun requestedPaths(): List<String> =
        List(server.requestCount) { server.takeRequest().path.orEmpty() }

    /**
     * What home's list query selects, whether or not the row has been paged in (U07a).
     *
     * The Feed asks for one page at a time now, so "is it in the list" and "is it on the
     * screen" are different questions — and the one this test is asking is the first.
     */
    private fun listedTitles(): List<String> = runBlocking {
        container.entries.observeEntries(includeRead = false).first().map { it.title }
    }

    private fun unreadTitles(): List<String> = runBlocking {
        database.entryDao().observeAll().first().filterNot { it.isRead }.map { it.title }
    }

    /** See `HomeScreenTest.awaitState` for why this polls in wall-clock time. */
    private fun awaitState(predicate: (HomeUiState) -> Boolean) =
        awaitCondition { predicate(viewModel.uiState.value) }

    private fun awaitCondition(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (predicate()) {
                // The predicate reads view-model state, which the composition has not
                // necessarily caught up with yet — the emission that satisfied it only
                // *schedules* a recomposition. Flush that before anyone looks at a node.
                compose.waitForIdle()
                return
            }
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out; last state was ${viewModel.uiState.value}")
    }

    private fun showHome(connectivity: ConnectivityMonitor = ConnectivityMonitor.AlwaysOnline) {
        viewModel = HomeViewModel(
            entries = container.entries,
            feeds = container.feeds,
            folders = container.folders,
            clock = clock,
            connectivity = connectivity,
            settings = settings,
        )
        val addSourceViewModel = AddSourceViewModel(container.feeds, container.folders)
        compose.setContent {
            PerchTheme(dynamicColor = false) {
                HomeScreen(
                    viewModel = viewModel,
                    addSourceViewModel = addSourceViewModel,
                    onOpenEntry = {},
                    onOpenSettings = {},
                )
            }
        }
        compose.waitUntil(TIMEOUT_MS) { !viewModel.uiState.value.isLoading }
        compose.waitForIdle()
    }

    /** A source the [MockWebServer] will actually serve, so a refresh has somewhere to go. */
    private fun seedRemoteFeed(
        path: String,
        title: String,
        entryTitle: String,
        lastError: String? = null,
    ): Long {
        bodies[path] = rss(title = title, entryTitle = entryTitle)
        return seedFeed(title = title, lastError = lastError, feedUrl = server.url(path).toString())
    }

    private fun rss(title: String, entryTitle: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0">
          <channel>
            <title>$title</title>
            <link>https://example.com</link>
            <item>
              <title>$entryTitle</title>
              <link>https://example.com/${entryTitle.hashCode()}</link>
              <guid>https://example.com/${entryTitle.hashCode()}</guid>
              <pubDate>Thu, 06 Aug 2026 09:00:00 GMT</pubDate>
              <description>A short summary.</description>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    private fun seedFeed(
        title: String,
        lastError: String? = null,
        feedUrl: String = "https://example.com/${title.hashCode()}/feed.xml",
    ): Long = runBlocking {
        database.feedDao().insert(
            FeedEntity(
                feedUrl = feedUrl,
                siteUrl = "https://example.com",
                title = title,
                customTitle = null,
                faviconUrl = null,
                etag = null,
                lastModified = null,
                lastFetchedAt = null,
                lastSuccessAt = null,
                lastError = lastError,
                addedAt = 0L,
            ),
        )
    }

    private fun seedEntry(
        feedId: Long,
        title: String,
        readAt: Long? = null,
    ): Long = runBlocking {
        val publishedAt = now.minusSeconds(2 * DAY).toEpochMilli()
        database.entryDao().insert(
            EntryEntity(
                feedId = feedId,
                guid = "guid-${title.hashCode()}",
                title = title,
                link = "https://example.com/post",
                author = null,
                publishedAt = publishedAt,
                publishedIsEstimated = false,
                summary = "A short summary.",
                contentHtml = "<p>A short summary.</p>",
                imageUrl = null,
                isRead = readAt != null,
                readAt = readAt,
                fetchedAt = publishedAt,
            ),
        )
    }

    private companion object {
        val FRESH = listOf("Fresh from one", "Fresh from two")
        const val ALL_CAUGHT_UP = "You're all caught up"
        const val DAY = 24 * 3_600L
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L
    }
}
