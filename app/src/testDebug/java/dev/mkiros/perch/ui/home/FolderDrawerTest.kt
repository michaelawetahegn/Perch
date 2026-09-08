package dev.mkiros.perch.ui.home

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
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.entity.FolderEntity
import dev.mkiros.perch.data.settings.SettingsStore
import dev.mkiros.perch.model.TimeFilter
import dev.mkiros.perch.support.PerchRule
import dev.mkiros.perch.support.testEntry
import dev.mkiros.perch.support.testFeed
import dev.mkiros.perch.ui.screenshot.awaitInRealTime
import dev.mkiros.perch.ui.screenshot.showHome as showHomeScreen
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
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

    @get:Rule(order = 1)
    val perch = PerchRule(clock = clock)

    // ---- the sections themselves --------------------------------------------------

    @Test
    fun `sources are listed under the folder they belong to`() {
        val graphics = seedFolder("Graphics")
        val security = seedFolder("Security")
        seedFeed(title = "GPUOpen", folderId = graphics)
        seedFeed(title = "Zero Day Initiative", folderId = security)

        showHome()
        expandInDrawer(graphics)
        expandInDrawer(security)

        assertThat(topOf("Graphics")).isLessThan(topOf("GPUOpen"))
        assertThat(topOf("GPUOpen")).isLessThan(topOf("Security"))
        assertThat(topOf("Security")).isLessThan(topOf("Zero Day Initiative"))
    }

    /**
     * V06/§0: alphabetical, case-insensitive, Uncategorized last. W03 took the *list* out
     * of this rule — the Feed is one chronological stream now — so the drawer is where
     * folder order lives, and `FolderDao.observeAll` is the only reader of it left.
     */
    @Test
    fun `the drawer orders folders alphabetically whatever order they were made in`() {
        // Created in reverse: while creation order decided, Security led the drawer.
        val security = seedFolder("Security")
        val ai = seedFolder("ai")
        seedEntry(seedFeed(title = "Zero Day Initiative", folderId = security), title = "advisory")
        seedEntry(seedFeed(title = "LLM Weekly", folderId = ai), title = "a model")

        showHome()
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

    /**
     * §0.1: every folder starts shut, Uncategorized included — a reader who opens the
     * drawer sees headers and nothing else.
     */
    @Test
    fun `the drawer opens with every folder shut, headers only`() {
        val graphics = seedFolder("Graphics")
        seedFeed(title = "GPUOpen", folderId = graphics)
        seedFeed(title = "nullprogram.com")

        showHome()
        openDrawer()

        compose.onNodeWithText("Graphics").assertIsDisplayed()
        compose.onNodeWithText(FolderEntity.UNCATEGORIZED_NAME).assertIsDisplayed()
        compose.onNodeWithText("GPUOpen").assertDoesNotExist()
        compose.onNodeWithText("nullprogram.com").assertDoesNotExist()
    }

    @Test
    fun `opening one folder reveals only that folder's sources`() {
        val graphics = seedFolder("Graphics")
        seedFeed(title = "GPUOpen", folderId = graphics)
        seedFeed(title = "nullprogram.com")

        showHome()
        expandInDrawer(graphics)

        compose.onNodeWithText("GPUOpen").assertIsDisplayed()
        compose.onNodeWithText("nullprogram.com").assertDoesNotExist()
    }

    @Test
    fun `expanding a folder hides its sources again on a second tap`() {
        val graphics = seedFolder("Graphics")
        seedFeed(title = "GPUOpen", folderId = graphics)

        showHome()
        expandInDrawer(graphics)
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

    /**
     * §0.3: the old collapsed-ids polarity kept this true as a side effect of the empty
     * set — nothing collapsed meant everything open, including a folder that did not exist
     * yet when the drawer opened. The invariant survives the inversion to expanded-ids
     * (X01); nothing pinned it until now.
     */
    @Test
    fun `a folder created while the drawer is open comes up expanded`() {
        showHome()
        openDrawer()
        compose.onNodeWithText("New folder").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag(FolderActionTestTags.NAME_FIELD)
            .performTextReplacement("Graphics")
        tap(FolderActionTestTags.NAME_CONFIRM)
        awaitState { state -> state.folders.any { it.name == "Graphics" } }
        val graphics = folders().first { it.name == "Graphics" }.id

        seedFeed(title = "GPUOpen", folderId = graphics)
        awaitDb { feedTitles().contains("GPUOpen") }

        compose.onNodeWithText("GPUOpen").assertIsDisplayed()
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

    private fun longPressInDrawer(label: String, folderId: Long = FolderEntity.UNCATEGORIZED_ID) {
        expandInDrawer(folderId)
        compose.onAllNodesWithText(label).filterToOne(hasClickAction())
            .performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
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
            tap(HomeTestTags.folderExpand(folderId))
        }
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

    /** A folder's name is on screen in the drawer *and* on every row it filed, so: by tag. */
    private fun topOfTag(testTag: String): Float =
        compose.onNodeWithTag(testTag).fetchSemanticsNode().positionInRoot.y

    private fun folders() = runBlocking { perch.database.folderDao().getAll() }

    private fun folderNames() = folders().map { it.name }

    private fun feeds() = runBlocking { perch.database.feedDao().getAll() }

    private fun feedTitles() = feeds().map { it.title }

    private fun folderIdOf(title: String) = feeds().first { it.title == title }.folderId

    /** Waits for a *later* database emission in wall-clock time (NOTES.md, T22). */
    private fun awaitState(predicate: (HomeUiState) -> Boolean) =
        compose.awaitInRealTime("a home state matching the test's predicate") {
            predicate(viewModel.uiState.value)
        }

    /** The same wait, for a fact that lives in the database rather than in the state. */
    private fun awaitDb(predicate: () -> Boolean) = awaitState { predicate() }

    private fun showHome() {
        viewModel = showHomeScreen(perch, compose, clock, settings).viewModel
    }

    private fun seedFolder(name: String): Long = runBlocking {
        perch.container.folders.createFolder(name)
    }

    private fun seedFeed(
        title: String,
        folderId: Long = FolderEntity.UNCATEGORIZED_ID,
    ): Long = runBlocking {
        perch.database.feedDao().insert(
            testFeed(
                title = title,
                folderId = folderId,
            ),
        )
    }

    private fun seedEntry(
        feedId: Long,
        title: String,
        readAt: Long? = null,
    ): Long = runBlocking {
        perch.database.entryDao().insert(
            testEntry(
                feedId = feedId,
                title = title,
                publishedAt = now.minusSeconds(DAY).toEpochMilli(),
                summary = "A short summary.",
                readAt = readAt,
                fetchedAt = now.toEpochMilli(),
            ),
        )
    }

    private companion object {
        const val DAY = 24 * 3_600L
    }
}
