package dev.mkiros.perch.ui.home

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
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
import dev.mkiros.perch.ui.source.AddSourceTestTags
import dev.mkiros.perch.ui.source.AddSourceViewModel
import dev.mkiros.perch.ui.theme.PerchTheme
import dev.mkiros.perch.ui.rowTitles
import dev.mkiros.perch.ui.screenshot.awaitInRealTime
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
 * The unified unread list (T21) and the source drawer around it (T22): what a row says,
 * what order rows come in, what the screen says when there is nothing to show, and what
 * selecting a source does to both the list and the bar.
 *
 * "Now" is a fixed [Clock] rather than the wall clock, so `3h` is an assertion and
 * not a race. Everything is seeded straight into an in-memory database — the screen is
 * driven by the same Room flows the real app uses, no fake repository in between.
 *
 * It lives in `src/testDebug` for the reason [dev.mkiros.perch.ui.nav.PerchNavHostTest]
 * records: `ComponentActivity` comes from `ui-test-manifest`, a `debugImplementation`.
 */
@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {

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

    @Test
    fun `a row shows its title and its source with a relative time, and no snippet`() {
        val feedId = seedFeed(title = "Null Program")
        seedEntry(
            feedId = feedId,
            title = "An Async Runtime in C",
            summary = "Coroutines without a language runtime, in about 200 lines.",
            publishedAt = now.minusSeconds(3 * HOUR),
        )

        showHome()

        compose.onNodeWithText("An Async Runtime in C").assertIsDisplayed()
        // The source name also appears in the drawer, which composes while closed, so the
        // row's own meta line is addressed by tag rather than by text.
        meta().assertTextEquals("Null Program")
        date().assertTextEquals("3h")
        // U08 dropped the snippet: the thumbnail does that work now, and the reference row
        // is title + metadata only.
        compose.onNodeWithText("Coroutines without a language runtime, in about 200 lines.")
            .assertDoesNotExist()
    }

    @Test
    fun `a renamed source shows under the name the reader gave it`() {
        val feedId = seedFeed(title = "nullprogram.com", customTitle = "Chris Wellons")
        seedEntry(feedId = feedId, title = "Practical libc-free threading")

        showHome()

        meta().assertTextEquals("Chris Wellons")
        date().assertTextEquals("2d")
    }

    /**
     * W03: the Feed is one stream. Two *folders*, not merely two sources — a single-folder
     * seed passes this on a list that groups by folder, and grouping by folder is exactly
     * what the reader asked us to stop doing.
     */
    @Test
    fun `entries from every source are interleaved newest first`() {
        val security = seedFolder("Security")
        val ai = seedFolder("AI")
        val one = seedFeed(title = "Source One", folderId = security)
        val two = seedFeed(title = "Source Two", folderId = ai)
        seedEntry(feedId = one, title = "Oldest", publishedAt = now.minusSeconds(3 * DAY))
        seedEntry(feedId = two, title = "Newest", publishedAt = now.minusSeconds(1 * HOUR))
        seedEntry(feedId = one, title = "Middle", publishedAt = now.minusSeconds(1 * DAY))

        showHome()

        assertThat(topOf("Newest")).isLessThan(topOf("Middle"))
        assertThat(topOf("Middle")).isLessThan(topOf("Oldest"))
    }

    /** And no header divides them: the ordering is the only sectioning there is. */
    @Test
    fun `the Feed draws no folder section header`() {
        val security = seedFolder("Security")
        val ai = seedFolder("AI")
        seedEntry(feedId = seedFeed(title = "ZDI", folderId = security), title = "An advisory")
        seedEntry(feedId = seedFeed(title = "LLM Weekly", folderId = ai), title = "A release")

        showHome()

        compose.onNodeWithText("An advisory").assertIsDisplayed()
        compose.onAllNodesWithTag(HomeTestTags.ENTRY).assertCountEquals(2)
        listOf(security, ai, FolderEntity.UNCATEGORIZED_ID).forEach { folderId ->
            compose.onNodeWithTag("home:section:$folderId").assertDoesNotExist()
        }
    }

    @Test
    fun `a read entry is not in the unread list`() {
        val feedId = seedFeed(title = "Source One")
        seedEntry(feedId = feedId, title = "Already read", readAt = now.toEpochMilli())
        seedEntry(feedId = feedId, title = "Still unread")

        showHome()

        compose.onNodeWithText("Still unread").assertIsDisplayed()
        compose.onNodeWithText("Already read").assertDoesNotExist()
    }

    @Test
    fun `tapping a row opens that entry`() {
        val feedId = seedFeed(title = "Source One")
        val entryId = seedEntry(feedId = feedId, title = "Something to read")
        val opened = mutableListOf<Long>()

        showHome(onOpenEntry = { opened += it })
        compose.onNodeWithText("Something to read").performClick()

        assertThat(opened).containsExactly(entryId)
    }

    @Test
    fun `with no sources the empty state asks for the first one`() {
        showHome()

        compose.onNodeWithText("Add your first source").assertIsDisplayed()
    }

    @Test
    fun `with sources but nothing unread the empty state says so`() {
        val feedId = seedFeed(title = "Source One")
        seedEntry(feedId = feedId, title = "Already read", readAt = now.toEpochMilli())

        showHome()

        compose.onNodeWithText("You're all caught up").assertIsDisplayed()
        compose.onNodeWithText("Add your first source").assertDoesNotExist()
    }

    // ---- adding a source, from the two places that offer it (T23) -----------------

    @Test
    fun `the drawer opens the add-source sheet`() {
        seedFeed(title = "Source One")

        showHome()
        openDrawer()
        compose.onNodeWithText("Add source")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        compose.onNodeWithTag(AddSourceTestTags.URL_FIELD).assertIsDisplayed()
    }

    @Test
    fun `the empty state opens the add-source sheet without going via the drawer`() {
        showHome()

        compose.onNodeWithText("Add your first source").assertIsDisplayed()
        compose.onNodeWithTag(HomeTestTags.EMPTY_ADD_SOURCE).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(AddSourceTestTags.URL_FIELD).assertIsDisplayed()
    }

    // ---- the source drawer and its filter (T22) ----------------------------------

    @Test
    fun `selecting a source lists only its entries and retitles the bar`() {
        val one = seedFeed(title = "Source One")
        val two = seedFeed(title = "Source Two")
        val three = seedFeed(title = "Source Three")
        seedEntry(feedId = one, title = "Only in one")
        seedEntry(feedId = two, title = "Only in two")
        seedEntry(feedId = three, title = "Only in three")

        showHome()
        selectInDrawer("Source Two")

        compose.onNodeWithText("Only in two").assertIsDisplayed()
        compose.onNodeWithText("Only in one").assertDoesNotExist()
        compose.onNodeWithText("Only in three").assertDoesNotExist()
        compose.onNodeWithTag(HomeTestTags.TITLE).assertTextEquals("Source Two")
    }

    @Test
    fun `a filtered source shows under the name the reader gave it`() {
        val renamed = seedFeed(title = "nullprogram.com", customTitle = "Chris Wellons")
        seedEntry(feedId = renamed, title = "Practical libc-free threading")

        showHome()
        selectInDrawer("Chris Wellons")

        compose.onNodeWithTag(HomeTestTags.TITLE).assertTextEquals("Chris Wellons")
    }

    @Test
    fun `going back to all sources clears the filter`() {
        val one = seedFeed(title = "Source One")
        val two = seedFeed(title = "Source Two")
        seedEntry(feedId = one, title = "Only in one")
        seedEntry(feedId = two, title = "Only in two")

        showHome()
        selectInDrawer("Source Two")
        selectInDrawer("All sources", expectedTitle = null)

        compose.onNodeWithText("Only in one").assertIsDisplayed()
        compose.onNodeWithText("Only in two").assertIsDisplayed()
        compose.onNodeWithTag(HomeTestTags.TITLE).assertTextEquals("Feed")
    }

    @Test
    fun `each drawer row is badged with that source's unread count`() {
        val two = seedFeed(title = "Two Unread")
        val one = seedFeed(title = "One Unread")
        val none = seedFeed(title = "Nothing Unread")
        seedEntry(feedId = two, title = "first")
        seedEntry(feedId = two, title = "second")
        seedEntry(feedId = one, title = "third")
        seedEntry(feedId = none, title = "fourth", readAt = now.toEpochMilli())

        showHome()
        openDrawer()

        badge(HomeTestTags.ALL_UNREAD_BADGE).assertTextEquals("3")
        badge(HomeTestTags.sourceBadge(two)).assertTextEquals("2")
        badge(HomeTestTags.sourceBadge(one)).assertTextEquals("1")
        // A fully-read source stays in the drawer at 0 rather than dropping out of it.
        badge(HomeTestTags.sourceBadge(none)).assertTextEquals("0")
    }

    @Test
    fun `a source whose last refresh failed is marked in the drawer`() {
        val healthy = seedFeed(title = "Healthy")
        seedFeed(title = "Broken", lastError = "Connection reset")
        seedEntry(feedId = healthy, title = "Something to read")

        showHome()
        openDrawer()

        compose.onNodeWithText("Broken").assertIsDisplayed()
        compose.onNodeWithContentDescription("Not updating").assertIsDisplayed()
    }

    @Test
    fun `reading the last unread entry of a filtered source leaves the source listed`() {
        val one = seedFeed(title = "Source One")
        seedFeed(title = "Source Two")
        seedEntry(feedId = one, title = "Only in one")

        showHome()
        selectInDrawer("Source One")
        runBlocking { container.entries.setRead(entryId = idOf("Only in one"), isRead = true) }
        compose.awaitInRealTime("the last unread entry to leave") { compose.rowTitles().isEmpty() }

        compose.onNodeWithText("You're all caught up").assertIsDisplayed()
        compose.onNodeWithTag(HomeTestTags.TITLE).assertTextEquals("Source One")
    }

    // ---- rename and remove, from a long press in the drawer (T24, re-aimed by U09a) --

    /**
     * The long press starts a selection now (U09a) rather than opening a menu, so T24's
     * two actions live on the contextual bar. They are offered only at exactly one ticked
     * row, which is the case this asserts.
     */
    @Test
    fun `long-pressing a source offers rename and remove`() {
        seedFeed(title = "Source One")

        showHome()
        longPressInDrawer("Source One")

        compose.onNodeWithTag(SelectionTestTags.RENAME).assertIsDisplayed()
        compose.onNodeWithTag(SelectionTestTags.DELETE).assertIsDisplayed()
    }

    @Test
    fun `removing a source asks before it does anything`() {
        seedFeed(title = "Source One")

        showHome()
        longPressInDrawer("Source One")
        tapDelete()

        compose.onNodeWithTag(SelectionTestTags.DELETE_CONFIRM).assertIsDisplayed()
        assertThat(feedTitles()).containsExactly("Source One")
    }

    @Test
    fun `cancelling the removal leaves the source and its entries alone`() {
        val one = seedFeed(title = "Source One")
        seedEntry(feedId = one, title = "Only in one")

        showHome()
        longPressInDrawer("Source One")
        tapDelete()
        tap(SelectionTestTags.DELETE_CANCEL)

        compose.onNodeWithTag(SelectionTestTags.DELETE_CONFIRM).assertDoesNotExist()
        assertThat(feedTitles()).containsExactly("Source One")
        assertThat(entryTitles()).containsExactly("Only in one")
    }

    @Test
    fun `confirming the removal takes the source's entries with it`() {
        val one = seedFeed(title = "Source One")
        val two = seedFeed(title = "Source Two")
        seedEntry(feedId = one, title = "Only in one")
        seedEntry(feedId = two, title = "Only in two")

        showHome()
        longPressInDrawer("Source One")
        tapDelete()
        tap(SelectionTestTags.DELETE_CONFIRM)
        awaitState { state -> state.sources.none { it.title == "Source One" } }

        assertThat(feedTitles()).containsExactly("Source Two")
        assertThat(entryTitles()).containsExactly("Only in two")
    }

    @Test
    fun `removing the source being filtered on drops the filter`() {
        val one = seedFeed(title = "Source One")
        val two = seedFeed(title = "Source Two")
        seedEntry(feedId = one, title = "Only in one")
        seedEntry(feedId = two, title = "Only in two")

        showHome()
        selectInDrawer("Source One")
        longPressInDrawer("Source One")
        tapDelete()
        tap(SelectionTestTags.DELETE_CONFIRM)
        awaitState { it.selectedTitle == null }

        compose.onNodeWithTag(HomeTestTags.TITLE).assertTextEquals("Feed")
        // Close the drawer the long press left open, then look at the list itself.
        selectInDrawer("All sources", expectedTitle = null)
        awaitDisplayed("Only in two")
        compose.onNodeWithText("Only in one").assertDoesNotExist()
    }

    @Test
    fun `renaming a source relabels the drawer without touching the feed's own title`() {
        val one = seedFeed(title = "nullprogram.com")
        seedEntry(feedId = one, title = "Practical libc-free threading")

        showHome()
        longPressInDrawer("nullprogram.com")
        tap(SelectionTestTags.RENAME)
        compose.onNodeWithTag(SourceActionTestTags.RENAME_FIELD)
            .performTextReplacement("Chris Wellons")
        tap(SourceActionTestTags.RENAME_CONFIRM)
        awaitState { state -> state.sources.any { it.title == "Chris Wellons" } }

        // The drawer is still open behind the dialog, so the relabelled row is on screen.
        // Addressed as a drawer row: since W04 the entry row prints the same name too.
        drawerRow("Chris Wellons").assertIsDisplayed()
        compose.onNodeWithText("nullprogram.com").assertDoesNotExist()
        // The rename is display-only: the parsed title is what the next refresh
        // overwrites, and what clearing the rename falls back to.
        val feed = feeds().single()
        assertThat(feed.customTitle).isEqualTo("Chris Wellons")
        assertThat(feed.title).isEqualTo("nullprogram.com")
    }

    @Test
    fun `cancelling the rename dialog changes nothing`() {
        seedFeed(title = "nullprogram.com")

        showHome()
        longPressInDrawer("nullprogram.com")
        tap(SelectionTestTags.RENAME)
        compose.onNodeWithTag(SourceActionTestTags.RENAME_FIELD)
            .performTextReplacement("Chris Wellons")
        tap(SourceActionTestTags.CANCEL)

        assertThat(feeds().single().customTitle).isNull()
        compose.onNodeWithText("nullprogram.com").assertIsDisplayed()
    }

    @Test
    fun `emptying the rename field restores the title the feed publishes`() {
        seedFeed(title = "nullprogram.com", customTitle = "Chris Wellons")

        showHome()
        longPressInDrawer("Chris Wellons")
        tap(SelectionTestTags.RENAME)
        compose.onNodeWithTag(SourceActionTestTags.RENAME_FIELD).performTextClearance()
        tap(SourceActionTestTags.RENAME_CONFIRM)
        awaitState { state -> state.sources.any { it.title == "nullprogram.com" } }

        assertThat(feeds().single().customTitle).isNull()
    }

    // ---- harness ---------------------------------------------------------------

    /** The row's two meta lines (W04), by tag: their text repeats elsewhere on screen. */
    private fun meta() = compose.onNodeWithTag(EntryRowTestTags.META, useUnmergedTree = true)

    private fun date() = compose.onNodeWithTag(EntryRowTestTags.DATE, useUnmergedTree = true)

    private fun openDrawer() {
        compose.onNodeWithContentDescription("Open sources").performClick()
        compose.waitForIdle()
    }

    /**
     * Selecting goes through the item's own `OnClick` semantics rather than an injected
     * tap: under Robolectric a synthesised touch does not reach a node inside the opened
     * drawer sheet, even though the node is on screen and carries the action. This still
     * runs the real composable's handler, the view model, and the Room query behind it.
     */

    /**
     * Opens the drawer and taps a row by its label, the way a reader filters. Selecting
     * re-runs the list query off the main thread, so this waits for the state the
     * assertions are about rather than for the click.
     */
    private fun selectInDrawer(label: String, expectedTitle: String? = label) {
        openDrawer()
        drawerRow(label).performSemanticsAction(SemanticsActions.OnClick)
        awaitState { it.selectedTitle == expectedTitle }
    }

    /**
     * Opens the drawer and long-presses a source, the way a reader reaches rename and
     * remove. Same reason as [selectInDrawer] for driving the semantics action directly.
     */
    private fun longPressInDrawer(label: String) {
        openDrawer()
        drawerRow(label).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
    }

    /**
     * The drawer row carrying [label], as opposed to the app bar showing the same name —
     * which it does whenever the source being long-pressed is also the one being filtered
     * on — or an entry row published by it, which since W04 prints the source name on its
     * own rather than welded to a time. Both of those and the drawer row answer a click,
     * so clicking is no longer enough to tell them apart; an entry row is excluded by the
     * tag every one of them carries.
     */
    private fun drawerRow(label: String) = compose.onAllNodesWithText(label)
        .filterToOne(hasClickAction() and !hasTestTag(HomeTestTags.ENTRY))

    /**
     * Taps a dialog button. The dialogs are in their own window rather than inside the
     * drawer sheet, but the same semantics route works and does not depend on where the
     * dialog happens to lay out.
     */
    private fun tap(testTag: String) {
        compose.onNodeWithTag(testTag).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    /**
     * The selection bar's delete, waited out (U09a). The confirmation cannot open until
     * the database has said how many saved or liked entries the batch holds, so the tap
     * and the dialog are a coroutine apart.
     */
    private fun tapDelete() {
        tap(SelectionTestTags.DELETE)
        awaitState { _ -> viewModel.sourceDeletePrompt.value != null }
    }

    /** A drawer badge is inside the item's merged semantics, so it needs the raw tree. */
    private fun badge(testTag: String) = compose.onNodeWithTag(testTag, useUnmergedTree = true)

    private fun feeds() = runBlocking { database.feedDao().observeAll().first() }

    private fun feedTitles() = feeds().map { it.title }

    private fun entryTitles() = runBlocking {
        database.entryDao().observeAll().first().map { it.title }
    }

    /**
     * Waits for a *later* database emission, in wall-clock time.
     *
     * [androidx.compose.ui.test.junit4.ComposeTestRule.waitUntil] only advances Compose's
     * virtual clock, and re-querying Room hops onto its query executor — a genuine
     * background thread under Robolectric — so a virtual-time spin can idle out the whole
     * timeout without that thread ever getting scheduled. The first emission is not
     * affected (composing the screen costs real time); every emission after it is.
     */
    private fun awaitState(predicate: (HomeUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (predicate(viewModel.uiState.value)) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out; last state was ${viewModel.uiState.value}")
    }

    /**
     * Waits for [text] to be on screen and *unobscured*, in wall-clock time.
     *
     * Distinct from [awaitState]: the state can already say what a test is waiting for
     * while the drawer that was covering the list is still animating shut, and a node
     * behind the scrim exists without being displayed. Selecting "All sources" after a
     * dialog is exactly that case — the filter was already null, so there is no state
     * change left to wait on.
     */
    private fun awaitDisplayed(text: String) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            runCatching { compose.onNodeWithText(text).assertIsDisplayed() }.onSuccess { return }
            Thread.sleep(POLL_MS)
        }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun idOf(title: String): Long = runBlocking {
        database.entryDao().observeAll().first().first { it.title == title }.id
    }


    private fun showHome(onOpenEntry: (Long) -> Unit = {}) {
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
                    onOpenEntry = onOpenEntry,
                    onOpenSettings = {},
                )
            }
        }
        // Room delivers its first emission off the main thread, so the skeleton is on
        // screen for a beat; wait for the state the assertions are actually about.
        compose.waitUntil(TIMEOUT_MS) { !viewModel.uiState.value.isLoading }
        compose.waitForIdle()
    }

    /** Y of a node's top edge in the root, for asserting one row is above another. */
    private fun topOf(text: String): Float =
        compose.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    private fun seedFolder(name: String): Long = runBlocking {
        database.folderDao().insert(FolderEntity(name = name, sortIndex = 0, createdAt = 0L))
    }

    private fun seedFeed(
        title: String,
        customTitle: String? = null,
        lastError: String? = null,
        folderId: Long = FolderEntity.UNCATEGORIZED_ID,
    ): Long = runBlocking {
        database.feedDao().insert(
            FeedEntity(
                feedUrl = "https://example.com/${title.hashCode()}/feed.xml",
                siteUrl = "https://example.com",
                title = title,
                customTitle = customTitle,
                faviconUrl = null,
                etag = null,
                lastModified = null,
                lastFetchedAt = null,
                lastSuccessAt = null,
                lastError = lastError,
                addedAt = 0L,
                folderId = folderId,
            ),
        )
    }

    private fun seedEntry(
        feedId: Long,
        title: String,
        summary: String? = "A short summary.",
        publishedAt: Instant = now.minusSeconds(2 * DAY),
        readAt: Long? = null,
    ): Long = runBlocking {
        database.entryDao().insert(
            EntryEntity(
                feedId = feedId,
                guid = "guid-${title.hashCode()}",
                title = title,
                link = "https://example.com/post",
                author = null,
                publishedAt = publishedAt.toEpochMilli(),
                publishedIsEstimated = false,
                summary = summary,
                contentHtml = "<p>$summary</p>",
                imageUrl = null,
                isRead = readAt != null,
                readAt = readAt,
                fetchedAt = publishedAt.toEpochMilli(),
            ),
        )
    }

    private companion object {
        const val HOUR = 3_600L
        const val DAY = 24 * HOUR
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
