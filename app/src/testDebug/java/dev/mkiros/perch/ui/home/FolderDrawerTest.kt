package dev.mkiros.perch.ui.home

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Folders in the drawer (U06): the sections, their counts, the four things a reader can do
 * to them, and what selecting one does to the list.
 *
 * Every assertion about a *change* is made against the database rather than against what
 * the drawer says — the drawer is downstream of Room here, so a test that only read labels
 * would pass on a screen that never wrote anything.
 *
 * Two Robolectric traps this file is shaped around (NOTES.md, T22): an injected tap never
 * reaches a node inside the opened drawer sheet, so clicks go through
 * `performSemanticsAction`; and `waitUntil` advances only the virtual clock, so waiting for
 * a later Room emission has to happen in wall-clock time.
 */
@RunWith(RobolectricTestRunner::class)
class FolderDrawerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: PerchDatabase
    private lateinit var container: AppContainer
    private lateinit var viewModel: HomeViewModel

    private val now = Instant.parse("2026-08-07T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

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
    }

    // ---- the sections themselves --------------------------------------------------

    @Test
    fun `sources are listed under the folder they belong to`() {
        val graphics = seedFolder("Graphics")
        val security = seedFolder("Security")
        seedFeed(title = "GPUOpen", folderId = graphics)
        seedFeed(title = "Zero Day Initiative", folderId = security)

        showHome()
        openDrawer()

        assertThat(topOf("Graphics")).isLessThan(topOf("GPUOpen"))
        assertThat(topOf("GPUOpen")).isLessThan(topOf("Security"))
        assertThat(topOf("Security")).isLessThan(topOf("Zero Day Initiative"))
    }

    /**
     * V06/§0: alphabetical, case-insensitive, Uncategorized last — and the drawer and the
     * list headers read *different* queries (`FolderDao.observeAll`, `EntryDao.LIST_ITEMS`),
     * so they are the pair that can disagree.
     */
    @Test
    fun `the drawer and the list sections agree on alphabetical folder order`() {
        // Created in reverse: while creation order decided, Security led both lists.
        val security = seedFolder("Security")
        val ai = seedFolder("ai")
        seedEntry(seedFeed(title = "Zero Day Initiative", folderId = security), title = "advisory")
        seedEntry(seedFeed(title = "LLM Weekly", folderId = ai), title = "a model")

        showHome()

        assertThat(topOfTag(HomeTestTags.section(ai)))
            .isLessThan(topOfTag(HomeTestTags.section(security)))

        openDrawer()

        assertThat(topOfTag(HomeTestTags.folderHeader(ai)))
            .isLessThan(topOfTag(HomeTestTags.folderHeader(security)))
    }

    @Test
    fun `a folder header carries the unread count of every source in it`() {
        val graphics = seedFolder("Graphics")
        val one = seedFeed(title = "GPUOpen", folderId = graphics)
        val two = seedFeed(title = "Real-Time Rendering", folderId = graphics)
        seedEntry(feedId = one, title = "first")
        seedEntry(feedId = one, title = "second")
        seedEntry(feedId = two, title = "third")
        seedEntry(feedId = two, title = "read already", readAt = now.toEpochMilli())

        showHome()
        openDrawer()

        badge(HomeTestTags.folderBadge(graphics)).assertTextEquals("3")
    }

    @Test
    fun `a folder with nothing unread is badged 0 rather than dropping out of the drawer`() {
        val graphics = seedFolder("Graphics")
        val feedId = seedFeed(title = "GPUOpen", folderId = graphics)
        seedEntry(feedId = feedId, title = "read already", readAt = now.toEpochMilli())

        showHome()
        openDrawer()

        badge(HomeTestTags.folderBadge(graphics)).assertTextEquals("0")
    }

    @Test
    fun `collapsing a folder hides its sources and leaves the header`() {
        val graphics = seedFolder("Graphics")
        seedFeed(title = "GPUOpen", folderId = graphics)

        showHome()
        openDrawer()
        compose.onNodeWithText("GPUOpen").assertIsDisplayed()
        tap(HomeTestTags.folderExpand(graphics))

        compose.onNodeWithText("GPUOpen").assertDoesNotExist()
        compose.onNodeWithText("Graphics").assertIsDisplayed()
    }

    @Test
    fun `selecting a folder lists only the entries of the sources in it`() {
        val graphics = seedFolder("Graphics")
        val inFolder = seedFeed(title = "GPUOpen", folderId = graphics)
        val outside = seedFeed(title = "Null Program")
        seedEntry(feedId = inFolder, title = "Only in Graphics")
        seedEntry(feedId = outside, title = "Somewhere else")

        showHome()
        openDrawer()
        tap(HomeTestTags.folderHeader(graphics))
        awaitState { it.selectedTitle == "Graphics" }

        compose.onNodeWithText("Only in Graphics").assertIsDisplayed()
        compose.onNodeWithText("Somewhere else").assertDoesNotExist()
        compose.onNodeWithTag(HomeTestTags.TITLE).assertTextEquals("Graphics")
    }

    // ---- creating, renaming, deleting ---------------------------------------------

    @Test
    fun `creating a folder from the drawer writes it to the database`() {
        showHome()
        openDrawer()
        compose.onNodeWithText("New folder").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag(FolderActionTestTags.NAME_FIELD)
            .performTextReplacement("Graphics")
        tap(FolderActionTestTags.NAME_CONFIRM)
        awaitState { state -> state.folders.any { it.name == "Graphics" } }

        assertThat(folderNames()).containsExactly(FolderEntity.UNCATEGORIZED_NAME, "Graphics")
    }

    @Test
    fun `renaming a folder from its overflow renames the row, not the sources in it`() {
        val graphics = seedFolder("Graphics")
        seedFeed(title = "GPUOpen", folderId = graphics)

        showHome()
        openDrawer()
        tap(HomeTestTags.folderOverflow(graphics))
        tap(FolderActionTestTags.RENAME)
        compose.onNodeWithTag(FolderActionTestTags.NAME_FIELD)
            .performTextReplacement("Rendering")
        tap(FolderActionTestTags.NAME_CONFIRM)
        awaitState { state -> state.folders.any { it.name == "Rendering" } }

        assertThat(folderNames()).containsExactly(FolderEntity.UNCATEGORIZED_NAME, "Rendering")
        assertThat(folderIdOf("GPUOpen")).isEqualTo(graphics)
    }

    @Test
    fun `deleting a folder moves its sources to Uncategorized instead of removing them`() {
        val graphics = seedFolder("Graphics")
        seedFeed(title = "GPUOpen", folderId = graphics)

        showHome()
        openDrawer()
        tap(HomeTestTags.folderOverflow(graphics))
        tap(FolderActionTestTags.DELETE)
        tap(FolderActionTestTags.DELETE_CONFIRM)
        awaitState { state -> state.folders.none { it.name == "Graphics" } }

        assertThat(folderNames()).containsExactly(FolderEntity.UNCATEGORIZED_NAME)
        assertThat(feedTitles()).containsExactly("GPUOpen")
        assertThat(folderIdOf("GPUOpen")).isEqualTo(FolderEntity.UNCATEGORIZED_ID)
    }

    @Test
    fun `Uncategorized offers neither rename nor delete`() {
        seedFeed(title = "GPUOpen")

        showHome()
        openDrawer()

        compose.onNodeWithText(FolderEntity.UNCATEGORIZED_NAME).assertIsDisplayed()
        compose.onNodeWithTag(HomeTestTags.folderOverflow(FolderEntity.UNCATEGORIZED_ID))
            .assertDoesNotExist()
    }

    // ---- moving a source ----------------------------------------------------------

    @Test
    fun `moving a source from its long press files it under the chosen folder`() {
        val graphics = seedFolder("Graphics")
        seedFeed(title = "GPUOpen")

        showHome()
        longPressInDrawer("GPUOpen")
        tap(SelectionTestTags.MOVE)
        compose.onNodeWithTag(FolderActionTestTags.folderChoice(graphics))
            .performSemanticsAction(SemanticsActions.OnClick)
        awaitDb { folderIdOf("GPUOpen") == graphics }

        assertThat(folderIdOf("GPUOpen")).isEqualTo(graphics)
        assertThat(feedTitles()).containsExactly("GPUOpen")
    }

    @Test
    fun `a source can be moved into a folder created from the move dialog`() {
        seedFeed(title = "GPUOpen")

        showHome()
        longPressInDrawer("GPUOpen")
        tap(SelectionTestTags.MOVE)
        tap(FolderActionTestTags.NEW_FOLDER)
        compose.onNodeWithTag(FolderActionTestTags.NAME_FIELD)
            .performTextReplacement("Graphics")
        tap(FolderActionTestTags.NAME_CONFIRM)
        awaitDb { folderIdOf("GPUOpen") != FolderEntity.UNCATEGORIZED_ID }

        val graphics = folders().first { it.name == "Graphics" }
        assertThat(folderIdOf("GPUOpen")).isEqualTo(graphics.id)
    }

    // ---- harness -------------------------------------------------------------------

    private fun openDrawer() {
        compose.onNodeWithContentDescription("Open sources").performClick()
        compose.waitForIdle()
    }

    private fun longPressInDrawer(label: String) {
        openDrawer()
        compose.onAllNodesWithText(label).filterToOne(hasClickAction())
            .performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
    }

    /** Drives a node's own click action: an injected tap never reaches the drawer sheet. */
    private fun tap(testTag: String) {
        compose.onNodeWithTag(testTag).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    /** A badge sits inside its row's merged semantics, so it needs the raw tree. */
    private fun badge(testTag: String) = compose.onNodeWithTag(testTag, useUnmergedTree = true)

    private fun topOf(text: String): Float =
        compose.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    /** A folder's name is on screen in the drawer *and* as a section header, so: by tag. */
    private fun topOfTag(testTag: String): Float =
        compose.onNodeWithTag(testTag).fetchSemanticsNode().positionInRoot.y

    private fun folders() = runBlocking { database.folderDao().getAll() }

    private fun folderNames() = folders().map { it.name }

    private fun feeds() = runBlocking { database.feedDao().getAll() }

    private fun feedTitles() = feeds().map { it.title }

    private fun folderIdOf(title: String) = feeds().first { it.title == title }.folderId

    /** Waits for a *later* database emission in wall-clock time (NOTES.md, T22). */
    private fun awaitState(predicate: (HomeUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (predicate(viewModel.uiState.value)) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out; last state was ${viewModel.uiState.value}")
    }

    /** The same wait, for a fact that lives in the database rather than in the state. */
    private fun awaitDb(predicate: () -> Boolean) = awaitState { predicate() }

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

    private fun seedFolder(name: String): Long = runBlocking {
        container.folders.createFolder(name)
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
        readAt: Long? = null,
    ): Long = runBlocking {
        database.entryDao().insert(
            EntryEntity(
                feedId = feedId,
                guid = "guid-${title.hashCode()}",
                title = title,
                link = "https://example.com/post",
                author = null,
                publishedAt = now.minusSeconds(DAY).toEpochMilli(),
                publishedIsEstimated = false,
                summary = "A short summary.",
                contentHtml = "<p>A short summary.</p>",
                imageUrl = null,
                isRead = readAt != null,
                readAt = readAt,
                fetchedAt = now.toEpochMilli(),
            ),
        )
    }

    private companion object {
        const val DAY = 24 * 3_600L
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
