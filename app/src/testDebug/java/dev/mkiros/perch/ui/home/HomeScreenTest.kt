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
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.ui.source.AddSourceTestTags
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
 * The unified unread list (T21) and the source drawer around it (T22): what a row says,
 * what order rows come in, what the screen says when there is nothing to show, and what
 * selecting a source does to both the list and the bar.
 *
 * "Now" is a fixed [Clock] rather than the wall clock, so `3h ago` is an assertion and
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
    fun `a row shows its title, its snippet, and its source with a relative time`() {
        val feedId = seedFeed(title = "Null Program")
        seedEntry(
            feedId = feedId,
            title = "An Async Runtime in C",
            summary = "Coroutines without a language runtime, in about 200 lines.",
            publishedAt = now.minusSeconds(3 * HOUR),
        )

        showHome()

        compose.onNodeWithText("An Async Runtime in C").assertIsDisplayed()
        compose.onNodeWithText("Coroutines without a language runtime, in about 200 lines.")
            .assertIsDisplayed()
        compose.onNodeWithText("Null Program · 3h ago").assertIsDisplayed()
    }

    @Test
    fun `a renamed source shows under the name the reader gave it`() {
        val feedId = seedFeed(title = "nullprogram.com", customTitle = "Chris Wellons")
        seedEntry(feedId = feedId, title = "Practical libc-free threading")

        showHome()

        compose.onNodeWithText("Chris Wellons · 2d ago").assertIsDisplayed()
    }

    @Test
    fun `entries from every source are interleaved newest first`() {
        val one = seedFeed(title = "Source One")
        val two = seedFeed(title = "Source Two")
        seedEntry(feedId = one, title = "Oldest", publishedAt = now.minusSeconds(3 * DAY))
        seedEntry(feedId = two, title = "Newest", publishedAt = now.minusSeconds(1 * HOUR))
        seedEntry(feedId = one, title = "Middle", publishedAt = now.minusSeconds(1 * DAY))

        showHome()

        assertThat(topOf("Newest")).isLessThan(topOf("Middle"))
        assertThat(topOf("Middle")).isLessThan(topOf("Oldest"))
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
    fun `going back to all unread clears the filter`() {
        val one = seedFeed(title = "Source One")
        val two = seedFeed(title = "Source Two")
        seedEntry(feedId = one, title = "Only in one")
        seedEntry(feedId = two, title = "Only in two")

        showHome()
        selectInDrawer("Source Two")
        selectInDrawer("All unread", expectedTitle = null)

        compose.onNodeWithText("Only in one").assertIsDisplayed()
        compose.onNodeWithText("Only in two").assertIsDisplayed()
        compose.onNodeWithTag(HomeTestTags.TITLE).assertTextEquals("Unread")
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
        awaitState { it.entries.isEmpty() }

        compose.onNodeWithText("You're all caught up").assertIsDisplayed()
        compose.onNodeWithTag(HomeTestTags.TITLE).assertTextEquals("Source One")
    }

    // ---- rename and remove, from a long press in the drawer (T24) -----------------

    @Test
    fun `long-pressing a source offers rename and remove`() {
        seedFeed(title = "Source One")

        showHome()
        longPressInDrawer("Source One")

        compose.onNodeWithTag(SourceActionTestTags.RENAME).assertIsDisplayed()
        compose.onNodeWithTag(SourceActionTestTags.REMOVE).assertIsDisplayed()
    }

    @Test
    fun `removing a source asks before it does anything`() {
        seedFeed(title = "Source One")

        showHome()
        longPressInDrawer("Source One")
        tap(SourceActionTestTags.REMOVE)

        compose.onNodeWithTag(SourceActionTestTags.REMOVE_CONFIRM).assertIsDisplayed()
        assertThat(feedTitles()).containsExactly("Source One")
    }

    @Test
    fun `cancelling the removal leaves the source and its entries alone`() {
        val one = seedFeed(title = "Source One")
        seedEntry(feedId = one, title = "Only in one")

        showHome()
        longPressInDrawer("Source One")
        tap(SourceActionTestTags.REMOVE)
        tap(SourceActionTestTags.CANCEL)

        compose.onNodeWithTag(SourceActionTestTags.REMOVE_CONFIRM).assertDoesNotExist()
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
        tap(SourceActionTestTags.REMOVE)
        tap(SourceActionTestTags.REMOVE_CONFIRM)
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
        tap(SourceActionTestTags.REMOVE)
        tap(SourceActionTestTags.REMOVE_CONFIRM)
        awaitState { it.selectedTitle == null }

        compose.onNodeWithTag(HomeTestTags.TITLE).assertTextEquals("Unread")
        // Close the drawer the long press left open, then look at the list itself.
        selectInDrawer("All unread", expectedTitle = null)
        awaitDisplayed("Only in two")
        compose.onNodeWithText("Only in one").assertDoesNotExist()
    }

    @Test
    fun `renaming a source relabels the drawer without touching the feed's own title`() {
        val one = seedFeed(title = "nullprogram.com")
        seedEntry(feedId = one, title = "Practical libc-free threading")

        showHome()
        longPressInDrawer("nullprogram.com")
        tap(SourceActionTestTags.RENAME)
        compose.onNodeWithTag(SourceActionTestTags.RENAME_FIELD)
            .performTextReplacement("Chris Wellons")
        tap(SourceActionTestTags.RENAME_CONFIRM)
        awaitState { state -> state.sources.any { it.title == "Chris Wellons" } }

        // The drawer is still open behind the dialog, so the relabelled row is on screen.
        compose.onNodeWithText("Chris Wellons").assertIsDisplayed()
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
        tap(SourceActionTestTags.RENAME)
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
        tap(SourceActionTestTags.RENAME)
        compose.onNodeWithTag(SourceActionTestTags.RENAME_FIELD).performTextClearance()
        tap(SourceActionTestTags.RENAME_CONFIRM)
        awaitState { state -> state.sources.any { it.title == "nullprogram.com" } }

        assertThat(feeds().single().customTitle).isNull()
    }

    // ---- harness ---------------------------------------------------------------

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
     * on. The row is the only one of the two that answers a click.
     */
    private fun drawerRow(label: String) =
        compose.onAllNodesWithText(label).filterToOne(hasClickAction())

    /**
     * Taps a dialog button. The dialogs are in their own window rather than inside the
     * drawer sheet, but the same semantics route works and does not depend on where the
     * dialog happens to lay out.
     */
    private fun tap(testTag: String) {
        compose.onNodeWithTag(testTag).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
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
     * behind the scrim exists without being displayed. Selecting "All unread" after a
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

    private fun seedFeed(
        title: String,
        customTitle: String? = null,
        lastError: String? = null,
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
