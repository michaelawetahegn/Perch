package dev.mkiros.perch.ui.home

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.ui.rowTitles
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.db.entity.FolderEntity
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.data.settings.SettingsStore
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.ui.source.AddSourceViewModel
import dev.mkiros.perch.ui.theme.PerchTheme
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PLAN-2 §0's two grouping dimensions on home (U07): time is a **filter**, folder is a
 * **section**, and they are not the same dimension.
 *
 * "Now" is a fixed [Clock] at noon UTC, so the window's edge is an assertion rather than
 * a race — a test that cannot pin *now* is not a test of a window measured from it.
 * Since W02/#15 that window is a **rolling** twenty-four hours, not the calendar day.
 */
@RunWith(RobolectricTestRunner::class)
class HomeTimeFilterTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: PerchDatabase
    private lateinit var container: AppContainer
    private lateinit var settings: SettingsStore
    private lateinit var viewModel: HomeViewModel

    private val now = Instant.parse("2026-08-07T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = PerchDatabase.inMemory(context)
        settings = SettingsStore.inMemory()
        container = AppContainer(
            database = database,
            httpClient = PerchHttp.client(cacheDir = null),
            clock = clock,
            settings = settings,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ---- the time filter ---------------------------------------------------------

    @Test
    fun `home opens on the last twenty-four hours, so late last night is still in`() {
        // W02/#15. U07 opened this window at local midnight and hid last night's
        // articles from a reader looking just after it — the emptiest possible Feed at
        // the moment they most often open one.
        val feedId = seedFeed("Source One")
        seedEntry(feedId, "This morning", at = "2026-08-07T09:00:00Z")
        seedEntry(feedId, "Late last night", at = "2026-08-06T23:59:00Z")

        showHome()

        assertThat(viewModel.uiState.value.timeFilter).isEqualTo(TimeFilter.Today)
        compose.onNodeWithText("This morning").assertIsDisplayed()
        compose.onNodeWithText("Late last night").assertIsDisplayed()
    }

    @Test
    fun `the list's edge is twenty-four hours back, and it holds`() {
        // A rolling window with no boundary assertion is an untested window. "Now" is
        // noon UTC, so these two straddle the edge by an hour on either side.
        val feedId = seedFeed("Source One")
        seedEntry(feedId, "Twenty-three hours old", at = "2026-08-06T13:00:00Z")
        seedEntry(feedId, "Twenty-five hours old", at = "2026-08-06T11:00:00Z")

        showHome()

        compose.onNodeWithText("Twenty-three hours old").assertIsDisplayed()
        compose.onNodeWithText("Twenty-five hours old").assertDoesNotExist()
    }

    @Test
    fun `an evening west of Greenwich lists the same twenty-four hours as anywhere else`() {
        // Issue #9 / V02, kept as a regression: 20:30 in Chicago is already 01:30 the
        // next day in UTC, and under U07's calendar window that turn of the UTC day
        // emptied the Feed. W02's rolling window cannot do that — it never asks what day
        // it is — and this is the screen saying so with the reported zone on the clock.
        val evening = Clock.fixed(Instant.parse("2026-08-09T01:30:00Z"), CHICAGO)
        val feedId = seedFeed("A Daily Blogger")
        seedEntry(feedId, "Posted this morning", at = "2026-08-08T14:00:00Z")

        showHome(clock = evening)

        compose.onNodeWithText("Posted this morning").assertIsDisplayed()
    }

    @Test
    fun `widening to the past week brings the last few days' entries back`() {
        val feedId = seedFeed("Source One")
        seedEntry(feedId, "This morning", at = "2026-08-07T09:00:00Z")
        seedEntry(feedId, "Three days ago", at = "2026-08-04T09:00:00Z")

        showHome()
        compose.onNodeWithText("Three days ago").assertDoesNotExist()

        chooseRange(TimeFilter.PastWeek)

        compose.onNodeWithText("This morning").assertIsDisplayed()
        compose.onNodeWithText("Three days ago").assertIsDisplayed()
    }

    @Test
    fun `a window wider than the entry still excludes what falls outside it`() {
        val feedId = seedFeed("Source One")
        seedEntry(feedId, "Last month", at = "2026-07-20T09:00:00Z")

        showHome()
        chooseRange(TimeFilter.PastWeek)
        compose.onNodeWithText("Last month").assertDoesNotExist()

        chooseRange(TimeFilter.PastMonth)
        compose.onNodeWithText("Last month").assertIsDisplayed()
    }

    @Test
    fun `all time keeps even an entry older than a year`() {
        val feedId = seedFeed("Source One")
        seedEntry(feedId, "Ancient history", at = "2019-01-01T09:00:00Z")

        showHome()
        chooseRange(TimeFilter.AllTime)

        compose.onNodeWithText("Ancient history").assertIsDisplayed()
    }

    @Test
    fun `the chosen range is remembered, not reset on the next launch`() {
        val feedId = seedFeed("Source One")
        seedEntry(feedId, "Last month", at = "2026-07-20T09:00:00Z")

        showHome()
        chooseRange(TimeFilter.PastMonth)
        assertThat(compose.rowTitles()).containsExactly("Last month")

        // A second view model over the same settings — process death, in miniature. The
        // range has to come out of DataStore, not out of the last view model's memory.
        val relaunched = HomeViewModel(
            entries = container.entries,
            feeds = container.feeds,
            folders = container.folders,
            clock = clock,
            settings = settings,
        )
        // `uiState` is `WhileSubscribed`, so it stays at its initial value until someone
        // collects it — and the collection has to be a coroutine on the main looper that
        // `waitForIdle` can run, never a `runBlocking` that would sit on that looper and
        // deadlock waiting for work only the looper can do.
        val seen = mutableListOf<HomeUiState>()
        val job = MainScope().launch { relaunched.uiState.collect { seen += it } }
        try {
            await({ "the relaunched view model never loaded" }) {
                seen.lastOrNull()?.isLoading == false
            }
            assertThat(seen.last().timeFilter).isEqualTo(TimeFilter.PastMonth)
        } finally {
            job.cancel()
        }
        // The rows are asserted on the live screen rather than on the relaunched view
        // model: since U07a they arrive as `PagingData`, which has no rows to read until
        // something collects it, and the only collector is a composition. What is being
        // defended is that the *remembered* range is the one the list was queried with,
        // and the screen above is running that query.
    }

    // ---- the empty bucket --------------------------------------------------------

    @Test
    fun `an empty window offers to widen rather than showing a blank screen`() {
        val feedId = seedFeed("Source One")
        seedEntry(feedId, "Three days ago", at = "2026-08-04T09:00:00Z")

        showHome()

        compose.onNodeWithTag(HomeTestTags.EMPTY_WIDEN).assertIsDisplayed()
        compose.onNodeWithText("Show Past Week instead").assertIsDisplayed()
    }

    @Test
    fun `taking the widen affordance widens the window`() {
        val feedId = seedFeed("Source One")
        seedEntry(feedId, "Three days ago", at = "2026-08-04T09:00:00Z")

        showHome()
        tap(HomeTestTags.EMPTY_WIDEN)
        awaitState { it.timeFilter == TimeFilter.PastWeek }

        compose.onNodeWithText("Three days ago").assertIsDisplayed()
    }

    @Test
    fun `an empty all-time inbox says the reader is caught up, with nothing to widen`() {
        val feedId = seedFeed("Source One")
        seedEntry(feedId, "Already read", at = "2026-08-07T09:00:00Z", read = true)

        showHome()
        chooseRange(TimeFilter.AllTime)

        compose.onNodeWithText("You're all caught up").assertIsDisplayed()
        compose.onNodeWithTag(HomeTestTags.EMPTY_WIDEN).assertDoesNotExist()
    }

    // ---- one chronological stream (W03) -------------------------------------------

    @Test
    fun `entries from two folders are mixed together, newest first`() {
        // Created out of alphabetical order on purpose: alphabetical folder order used to
        // outrank recency here, and a seed already sorted could not tell the two apart.
        val security = seedFolder("Security", sortIndex = 0)
        val ai = seedFolder("AI", sortIndex = 1)
        val zdi = seedFeed("ZDI", folderId = security)
        val llm = seedFeed("LLM Weekly", folderId = ai)
        seedEntry(zdi, "Newest of all", at = "2026-08-07T11:00:00Z")
        seedEntry(llm, "A model release", at = "2026-08-07T08:00:00Z")

        showHome()

        assertThat(topOf("Newest of all")).isLessThan(topOf("A model release"))
        assertThat(sectionExists(ai)).isFalse()
        assertThat(sectionExists(security)).isFalse()
    }

    @Test
    fun `an uncategorized entry takes its place by date like any other`() {
        val ai = seedFolder("AI", sortIndex = 9)
        val loose = seedFeed("Unfiled Source")
        val inAi = seedFeed("LLM Weekly", folderId = ai)
        seedEntry(loose, "Unfiled entry", at = "2026-08-07T11:00:00Z")
        seedEntry(inAi, "Filed entry", at = "2026-08-07T08:00:00Z")

        showHome()

        assertThat(topOf("Unfiled entry")).isLessThan(topOf("Filed entry"))
        assertThat(sectionExists(FolderEntity.UNCATEGORIZED_ID)).isFalse()
    }

    @Test
    fun `scoping the drawer to one folder still narrows the list`() {
        val ai = seedFolder("AI", sortIndex = 0)
        val inAi = seedFeed("LLM Weekly", folderId = ai)
        seedFeed("Unfiled Source").let { seedEntry(it, "Unfiled entry") }
        seedEntry(inAi, "Filed entry")

        showHome()
        compose.onNodeWithText("Unfiled entry").assertIsDisplayed()

        selectFolderInDrawer("AI")

        compose.onNodeWithText("Filed entry").assertIsDisplayed()
        compose.onNodeWithText("Unfiled entry").assertDoesNotExist()
    }

    // ---- harness -----------------------------------------------------------------

    /** Opens U08a's dropdown and picks a range from it, as the reader does. */
    private fun chooseRange(filter: TimeFilter) {
        tap(HomeTestTags.TIME_RANGE)
        tap(HomeTestTags.rangeItem(filter))
        awaitState { it.timeFilter == filter }
    }

    private fun tap(testTag: String) {
        compose.onNodeWithTag(testTag).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun selectFolderInDrawer(name: String) {
        compose.onNodeWithContentDescription("Open sources").performClick()
        compose.waitForIdle()
        tap(HomeTestTags.folderHeader(folderIdOf(name)))
        awaitState { it.selectedTitle == name }
        compose.waitForIdle()
    }

    private fun folderIdOf(name: String): Long = runBlocking {
        database.folderDao().getAll().first { it.name == name }.id
    }

    /** See `HomeScreenTest.awaitState`: Room's emissions need wall-clock time. */
    private fun awaitState(predicate: (HomeUiState) -> Boolean) =
        await({ "timed out; last state was ${viewModel.uiState.value}" }) {
            predicate(viewModel.uiState.value)
        }

    /** Polls [predicate] in wall-clock time, running the looper between attempts. */
    private fun await(message: () -> String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (predicate()) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError(message())
    }

    private fun topOf(text: String): Float =
        compose.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    /**
     * Whether the list drew a folder header for [folderId]. The tag is spelled out rather
     * than read from `HomeTestTags`, because W03 deleted the constant and the point of
     * these assertions is that nothing puts one back.
     */
    private fun sectionExists(folderId: Long): Boolean = compose
        .onAllNodesWithTag("home:section:$folderId").fetchSemanticsNodes().isNotEmpty()

    private fun showHome(clock: Clock = this.clock) {
        viewModel = HomeViewModel(
            entries = container.entries,
            feeds = container.feeds,
            folders = container.folders,
            clock = clock,
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

    private fun seedFolder(name: String, sortIndex: Int): Long = runBlocking {
        database.folderDao().insert(
            FolderEntity(name = name, sortIndex = sortIndex, createdAt = 0L),
        )
    }

    private fun seedFeed(
        title: String,
        folderId: Long = FolderEntity.UNCATEGORIZED_ID,
    ): Long = runBlocking {
        database.feedDao().insert(
            FeedEntity(
                feedUrl = "https://example.com/${title.hashCode()}/feed.xml",
                siteUrl = "https://example.com",
                title = title,
                customTitle = null,
                faviconUrl = null,
                etag = null,
                lastModified = null,
                lastFetchedAt = null,
                lastSuccessAt = null,
                lastError = null,
                addedAt = 0L,
                folderId = folderId,
            ),
        )
    }

    private fun seedEntry(
        feedId: Long,
        title: String,
        at: String = "2026-08-07T09:00:00Z",
        read: Boolean = false,
    ): Long = runBlocking {
        val published = Instant.parse(at).toEpochMilli()
        database.entryDao().insert(
            EntryEntity(
                feedId = feedId,
                guid = "guid-${title.hashCode()}",
                title = title,
                link = "https://example.com/post",
                author = null,
                publishedAt = published,
                publishedIsEstimated = false,
                summary = null,
                contentHtml = null,
                imageUrl = null,
                isRead = read,
                readAt = if (read) published else null,
                fetchedAt = published,
            ),
        )
    }

    private companion object {
        val CHICAGO: ZoneId = ZoneId.of("America/Chicago")
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
    }
}
