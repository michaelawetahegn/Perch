package dev.mkiros.perch.ui.home

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Multi-select in the drawer (PLAN-2 U09a): the mode, its two rules, its two very
 * different deletes, and the way out of it.
 *
 * The deletes are asserted against the database rather than against the drawer, because
 * the claim being made is about *sources surviving a folder delete* — a claim the drawer
 * cannot make, since it renders whatever Room hands it either way.
 *
 * Same two Robolectric traps as U06's file (NOTES.md, T22): an injected tap never reaches
 * a node inside the opened drawer sheet, so everything goes through
 * `performSemanticsAction`; and `waitUntil` advances only the virtual clock, so a later
 * Room emission has to be waited on in wall-clock time.
 */
@RunWith(RobolectricTestRunner::class)
class DrawerMultiSelectTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: PerchDatabase
    private lateinit var container: AppContainer
    private lateinit var viewModel: HomeViewModel
    private lateinit var drawerState: DrawerState
    private lateinit var selection: MutableState<DrawerSelection>

    private val now = Instant.parse("2026-08-08T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    /** Home opens on Today; these entries are seeded a day back (NOTES.md, U07). */
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

    // ---- entering, toggling, leaving ------------------------------------------------

    @Test
    fun `long-pressing a source enters selection mode holding that source`() {
        val gpuopen = seedFeed(title = "GPUOpen")
        seedFeed(title = "nullprogram.com")

        showHome()
        longPress("GPUOpen")

        compose.onNodeWithTag(SelectionTestTags.COUNT).assertTextEquals("1 selected")
        checkbox(SelectionTestTags.sourceCheckbox(gpuopen)).assertIsOn()
        // Selection mode replaces the drawer's navigation, so a stray tap cannot leave it.
        compose.onNodeWithText("All unread").assertDoesNotExist()
    }

    @Test
    fun `tapping rows adds and removes them from the selection`() {
        val gpuopen = seedFeed(title = "GPUOpen")
        val nullprogram = seedFeed(title = "nullprogram.com")

        showHome()
        longPress("GPUOpen")
        tapRow("nullprogram.com")

        compose.onNodeWithTag(SelectionTestTags.COUNT).assertTextEquals("2 selected")
        checkbox(SelectionTestTags.sourceCheckbox(nullprogram)).assertIsOn()

        tapRow("GPUOpen")
        compose.onNodeWithTag(SelectionTestTags.COUNT).assertTextEquals("1 selected")
        checkbox(SelectionTestTags.sourceCheckbox(gpuopen)).assertIsOff()
    }

    @Test
    fun `unticking the last row leaves selection mode`() {
        seedFeed(title = "GPUOpen")

        showHome()
        longPress("GPUOpen")
        tapRow("GPUOpen")

        compose.onNodeWithTag(SelectionTestTags.BAR).assertDoesNotExist()
        compose.onNodeWithText("All unread").assertIsDisplayed()
    }

    @Test
    fun `the close action leaves selection and gives the drawer back`() {
        seedFeed(title = "GPUOpen")

        showHome()
        longPress("GPUOpen")
        tap(SelectionTestTags.CLOSE)

        compose.onNodeWithTag(SelectionTestTags.BAR).assertDoesNotExist()
        assertThat(drawerState.isOpen).isTrue()
    }

    // ---- the two rules --------------------------------------------------------------

    @Test
    fun `a selection started on a source will not take a folder`() {
        val graphics = seedFolder("Graphics")
        seedFeed(title = "GPUOpen", folderId = graphics)

        showHome()
        longPress("GPUOpen")

        // The rule is drawn as well as enforced: mid-source-selection a folder header
        // carries no tick at all, and neither pressing it nor long-pressing it takes one.
        checkbox(SelectionTestTags.folderCheckbox(graphics)).assertDoesNotExist()
        tapFolder(graphics)
        longPressFolder(graphics)

        assertThat(selection.value).isInstanceOf(DrawerSelection.Sources::class.java)
        compose.onNodeWithTag(SelectionTestTags.COUNT).assertTextEquals("1 selected")
    }

    @Test
    fun `a selection started on a folder will not take a source`() {
        val graphics = seedFolder("Graphics")
        val gpuopen = seedFeed(title = "GPUOpen", folderId = graphics)

        showHome()
        longPressFolder(graphics)

        checkbox(SelectionTestTags.sourceCheckbox(gpuopen)).assertDoesNotExist()
        tapRow("GPUOpen")

        assertThat(selection.value).isEqualTo(DrawerSelection.Folders(setOf(graphics)))
        compose.onNodeWithTag(SelectionTestTags.COUNT).assertTextEquals("1 selected")
    }

    @Test
    fun `Uncategorized cannot be ticked in a folder selection`() {
        val graphics = seedFolder("Graphics")
        seedFeed(title = "GPUOpen", folderId = graphics)
        seedFeed(title = "nullprogram.com")

        showHome()
        longPressFolder(graphics)
        checkbox(SelectionTestTags.folderCheckbox(FolderEntity.UNCATEGORIZED_ID))
            .assertIsOff()
        longPressFolder(FolderEntity.UNCATEGORIZED_ID)

        assertThat(selection.value).isEqualTo(DrawerSelection.Folders(setOf(graphics)))
    }

    // ---- deleting folders: reversible, so a snackbar --------------------------------

    @Test
    fun `deleting folders keeps every source and moves them to Uncategorized`() {
        val graphics = seedFolder("Graphics")
        val security = seedFolder("Security")
        seedFeed(title = "GPUOpen", folderId = graphics)
        seedFeed(title = "Zero Day Initiative", folderId = security)

        showHome()
        longPressFolder(graphics)
        tapFolder(security)
        tap(SelectionTestTags.DELETE)
        awaitDb { folderNames() == listOf(FolderEntity.UNCATEGORIZED_NAME) }

        assertThat(feedTitles()).containsExactly("GPUOpen", "Zero Day Initiative")
        assertThat(feeds().map { it.folderId })
            .containsExactly(FolderEntity.UNCATEGORIZED_ID, FolderEntity.UNCATEGORIZED_ID)
        compose.onNodeWithText("2 folders deleted · 2 sources moved to Uncategorized")
            .assertIsDisplayed()
    }

    @Test
    fun `undoing a folder delete restores the folders and their sources' membership`() {
        val graphics = seedFolder("Graphics")
        val security = seedFolder("Security")
        seedFeed(title = "GPUOpen", folderId = graphics)
        seedFeed(title = "Zero Day Initiative", folderId = security)

        showHome()
        longPressFolder(graphics)
        tapFolder(security)
        tap(SelectionTestTags.DELETE)
        awaitDb { folderNames() == listOf(FolderEntity.UNCATEGORIZED_NAME) }

        compose.onNodeWithText("Undo").performSemanticsAction(SemanticsActions.OnClick)
        awaitDb { folderNames().size == 3 }

        assertThat(folderNames())
            .containsExactly("Graphics", "Security", FolderEntity.UNCATEGORIZED_NAME)
        assertThat(folderIdOf("GPUOpen")).isEqualTo(graphics)
        assertThat(folderIdOf("Zero Day Initiative")).isEqualTo(security)
    }

    // ---- deleting sources: not reversible, so a dialog -------------------------------

    @Test
    fun `deleting sources asks first and names what the reader kept`() {
        val gpuopen = seedFeed(title = "GPUOpen")
        val nullprogram = seedFeed(title = "nullprogram.com")
        seedEntry(gpuopen, "Saved one", isSaved = true)
        seedEntry(gpuopen, "Liked one", isLiked = true)
        seedEntry(nullprogram, "Just read")

        showHome()
        longPress("GPUOpen")
        tapRow("nullprogram.com")
        tapDelete()

        compose.onNodeWithText("Remove 2 sources?").assertIsDisplayed()
        compose.onNodeWithTag(SelectionTestTags.DELETE_CURATED)
            .assertTextEquals("2 saved or liked articles will be deleted.")
        // Nothing has happened yet — the dialog is the whole point.
        assertThat(feedTitles()).containsExactly("GPUOpen", "nullprogram.com")
    }

    @Test
    fun `a batch holding nothing saved or liked says nothing about it`() {
        seedFeed(title = "GPUOpen").also { seedEntry(it, "Just read") }

        showHome()
        longPress("GPUOpen")
        tapDelete()

        compose.onNodeWithText("Remove 1 source?").assertIsDisplayed()
        compose.onNodeWithTag(SelectionTestTags.DELETE_CURATED).assertDoesNotExist()
    }

    @Test
    fun `cancelling a source delete keeps the batch the reader assembled`() {
        seedFeed(title = "GPUOpen")
        seedFeed(title = "nullprogram.com")

        showHome()
        longPress("GPUOpen")
        tapRow("nullprogram.com")
        tapDelete()
        tap(SelectionTestTags.DELETE_CANCEL)

        assertThat(feedTitles()).containsExactly("GPUOpen", "nullprogram.com")
        compose.onNodeWithTag(SelectionTestTags.COUNT).assertTextEquals("2 selected")
    }

    @Test
    fun `confirming a source delete removes the whole batch and leaves selection`() {
        val gpuopen = seedFeed(title = "GPUOpen")
        seedFeed(title = "nullprogram.com")
        val kept = seedFeed(title = "Zero Day Initiative")
        seedEntry(gpuopen, "Goes with it", isSaved = true)
        seedEntry(kept, "Stays")

        showHome()
        longPress("GPUOpen")
        tapRow("nullprogram.com")
        tapDelete()
        tap(SelectionTestTags.DELETE_CONFIRM)
        awaitDb { feedTitles() == listOf("Zero Day Initiative") }

        assertThat(entryTitles()).containsExactly("Stays")
        compose.onNodeWithTag(SelectionTestTags.BAR).assertDoesNotExist()
    }

    // ---- the back chain's first rung -------------------------------------------------

    @Test
    fun `back leaves selection before it closes the drawer`() {
        seedFeed(title = "GPUOpen")

        showHome()
        longPress("GPUOpen")
        back()

        compose.onNodeWithTag(SelectionTestTags.BAR).assertDoesNotExist()
        assertThat(selection.value).isEqualTo(DrawerSelection.None)
        // The rung under test: the drawer, which back would otherwise have taken, is
        // untouched. Closing it is the *next* press, and U09 already owns that rung.
        assertThat(drawerState.isOpen).isTrue()
        compose.onNodeWithText("All unread").assertIsDisplayed()
    }

    // ---- harness ---------------------------------------------------------------------

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun openDrawer() {
        if (drawerState.isClosed) {
            compose.onNodeWithContentDescription("Open sources").performClick()
        }
        compose.waitForIdle()
    }

    private fun longPress(label: String) {
        openDrawer()
        row(label).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
    }

    private fun tapRow(label: String) {
        row(label).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun row(label: String) =
        compose.onAllNodesWithText(label).filterToOne(hasClickAction())

    private fun longPressFolder(folderId: Long) {
        openDrawer()
        compose.onNodeWithTag(HomeTestTags.folderHeader(folderId))
            .performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
    }

    private fun tapFolder(folderId: Long) {
        compose.onNodeWithTag(HomeTestTags.folderHeader(folderId))
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    /**
     * The delete action, waited out. A source batch's dialog cannot open until the
     * database has said how many saved or liked entries the batch holds, so the tap and
     * the dialog are a coroutine apart.
     */
    private fun tapDelete() {
        tap(SelectionTestTags.DELETE)
        awaitDb { viewModel.sourceDeletePrompt.value != null }
    }

    /** Drives a node's own click action: an injected tap never reaches the drawer sheet. */
    private fun tap(testTag: String) {
        compose.onNodeWithTag(testTag).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    /** A checkbox sits inside its row's merged semantics, so it needs the raw tree. */
    private fun checkbox(testTag: String) =
        compose.onNodeWithTag(testTag, useUnmergedTree = true)

    private fun folders() = runBlocking { database.folderDao().getAll() }

    private fun folderNames() = folders().map { it.name }

    private fun feeds() = runBlocking { database.feedDao().getAll() }

    private fun feedTitles() = feeds().map { it.title }

    private fun entryTitles() = runBlocking {
        database.entryDao().observeAll().first().map { it.title }
    }

    private fun folderIdOf(title: String) = feeds().first { it.title == title }.folderId

    /** Waits for a *later* database emission in wall-clock time (NOTES.md, T22). */
    private fun awaitDb(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (predicate()) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out waiting on the database")
    }

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
            // Hoisted exactly as PerchNavHost hoists them, so the test can ask the same
            // questions the shell's back chain asks.
            drawerState = rememberDrawerState(DrawerValue.Closed)
            selection = rememberSaveable(stateSaver = DrawerSelection.Saver) {
                mutableStateOf<DrawerSelection>(DrawerSelection.None)
            }
            PerchTheme(dynamicColor = false) {
                HomeScreen(
                    viewModel = viewModel,
                    addSourceViewModel = addSourceViewModel,
                    onOpenEntry = {},
                    onOpenSettings = {},
                    drawerState = drawerState,
                    selection = selection,
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
        isSaved: Boolean = false,
        isLiked: Boolean = false,
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
                isRead = false,
                readAt = null,
                isSaved = isSaved,
                savedAt = if (isSaved) now.toEpochMilli() else null,
                isStarred = isLiked,
                starredAt = if (isLiked) now.toEpochMilli() else null,
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
