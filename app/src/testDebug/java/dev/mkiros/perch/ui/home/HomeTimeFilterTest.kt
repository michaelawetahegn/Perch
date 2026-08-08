package dev.mkiros.perch.ui.home

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
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
 * "Now" is a fixed [Clock] at noon UTC, so "since local midnight" is an assertion rather
 * than a race — a test that cannot pin *today* is not a test of Today.
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
    fun `home opens on today, which starts at local midnight and not a day ago`() {
        val feedId = seedFeed("Source One")
        seedEntry(feedId, "This morning", at = "2026-08-07T09:00:00Z")
        seedEntry(feedId, "Late last night", at = "2026-08-06T23:59:00Z")

        showHome()

        assertThat(viewModel.uiState.value.timeFilter).isEqualTo(TimeFilter.Today)
        compose.onNodeWithText("This morning").assertIsDisplayed()
        compose.onNodeWithText("Late last night").assertDoesNotExist()
    }

    @Test
    fun `widening to the past week brings last night's entries back`() {
        val feedId = seedFeed("Source One")
        seedEntry(feedId, "This morning", at = "2026-08-07T09:00:00Z")
        seedEntry(feedId, "Late last night", at = "2026-08-06T23:59:00Z")

        showHome()
        chooseRange(TimeFilter.PastWeek)

        compose.onNodeWithText("This morning").assertIsDisplayed()
        compose.onNodeWithText("Late last night").assertIsDisplayed()
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
            assertThat(seen.last().entries.map { it.title }).containsExactly("Last month")
        } finally {
            job.cancel()
        }
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

    // ---- folder sections ---------------------------------------------------------

    @Test
    fun `entries are sectioned under folder headers in folder order`() {
        val security = seedFolder("Security", sortIndex = 0)
        val ai = seedFolder("AI", sortIndex = 1)
        val zdi = seedFeed("ZDI", folderId = security)
        val llm = seedFeed("LLM Weekly", folderId = ai)
        // Newest overall is in the *second* section: folder order outranks recency
        // across sections, and recency orders rows within one.
        seedEntry(llm, "Newest of all", at = "2026-08-07T11:00:00Z")
        seedEntry(zdi, "An advisory", at = "2026-08-07T08:00:00Z")

        showHome()

        // Headers are addressed by tag, not by text: the drawer behind the list is
        // composed even while closed, so every folder name is on screen twice.
        assertThat(topOfSection(security)).isLessThan(topOf("An advisory"))
        assertThat(topOf("An advisory")).isLessThan(topOfSection(ai))
        assertThat(topOfSection(ai)).isLessThan(topOf("Newest of all"))
        compose.onNodeWithTag(HomeTestTags.section(security)).assertTextEquals("Security")
    }

    @Test
    fun `uncategorized sections last however the other folders sort`() {
        val ai = seedFolder("AI", sortIndex = 9)
        val loose = seedFeed("Unfiled Source")
        val inAi = seedFeed("LLM Weekly", folderId = ai)
        seedEntry(loose, "Unfiled entry", at = "2026-08-07T11:00:00Z")
        seedEntry(inAi, "Filed entry", at = "2026-08-07T08:00:00Z")

        showHome()

        assertThat(topOf("Filed entry"))
            .isLessThan(topOfSection(FolderEntity.UNCATEGORIZED_ID))
        assertThat(topOfSection(FolderEntity.UNCATEGORIZED_ID)).isLessThan(topOf("Unfiled entry"))
    }

    @Test
    fun `scoping the drawer to one folder collapses the headers away`() {
        val ai = seedFolder("AI", sortIndex = 0)
        val inAi = seedFeed("LLM Weekly", folderId = ai)
        seedFeed("Unfiled Source").let { seedEntry(it, "Unfiled entry") }
        seedEntry(inAi, "Filed entry")

        showHome()
        // There are two sections to begin with, so the headers are earning their space.
        compose.onNodeWithTag(HomeTestTags.section(ai)).assertIsDisplayed()

        selectFolderInDrawer("AI")

        compose.onNodeWithText("Filed entry").assertIsDisplayed()
        compose.onNodeWithTag(HomeTestTags.section(ai)).assertDoesNotExist()
    }

    @Test
    fun `one folder is no sections at all — a header over the whole list says nothing`() {
        val feedId = seedFeed("Source One")
        seedEntry(feedId, "Something to read")

        showHome()

        compose.onNodeWithText("Something to read").assertIsDisplayed()
        compose.onNodeWithTag(HomeTestTags.section(FolderEntity.UNCATEGORIZED_ID))
            .assertDoesNotExist()
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

    private fun topOfSection(folderId: Long): Float = compose
        .onNodeWithTag(HomeTestTags.section(folderId)).fetchSemanticsNode().positionInRoot.y

    private fun showHome() {
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
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
    }
}
