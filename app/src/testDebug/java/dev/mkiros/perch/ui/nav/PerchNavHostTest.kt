package dev.mkiros.perch.ui.nav

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.data.settings.SettingsStore
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.data.repo.PerchPaging
import dev.mkiros.perch.ui.home.HomeTestTags
import dev.mkiros.perch.ui.home.TimeFilter
import dev.mkiros.perch.ui.screenshot.awaitInRealTime
import dev.mkiros.perch.ui.theme.PerchTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock

/**
 * The shell: every route reachable, none of them crashing, the bottom bar showing where it
 * should and gone where it should not, and back doing §0's chain rather than quitting.
 *
 * The screens themselves are still stubs (T21–T27 fill them in), so what is asserted here
 * is only what the scaffold owns — that a destination composes with a real [AppContainer]
 * behind it, and that the system back gesture unwinds the stack rather than leaving the
 * reader stranded on an article.
 *
 * It lives in `src/testDebug`, not `src/test`: the empty `ComponentActivity` it hosts the graph
 * in comes from `ui-test-manifest`, a `debugImplementation`, so the release unit-test variant
 * has no such activity in its merged manifest. Every Compose test belongs in this source set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class PerchNavHostTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: PerchDatabase
    private lateinit var container: AppContainer
    private lateinit var navController: NavHostController

    /**
     * Home opens on Today (U07), and the entries seeded here are not from today — so the
     * shell tests would all be assertions about an empty list without this.
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
            clock = Clock.systemUTC(),
            settings = settings,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `home is the start destination`() {
        showNavHost()

        assertThat(currentRoute()).isEqualTo(Routes.FEED)
        // The tab is called "Feed" too, so pin the *title* — otherwise the bottom bar
        // answers for the screen and the assertion never sees the top bar at all.
        compose.onNodeWithTag(HomeTestTags.TITLE).assertTextEquals("Feed")
    }

    @Test
    fun `the article route composes the entry it was given`() {
        val entryId = seedOneEntry(title = "A standardized reading surface")
        showNavHost()

        navigateTo(Routes.article(entryId))

        assertThat(currentRoute()).isEqualTo(Routes.ARTICLE)
        awaitText("A standardized reading surface")
    }

    @Test
    fun `the settings route composes`() {
        showNavHost()

        navigateTo(Routes.SETTINGS)

        assertThat(currentRoute()).isEqualTo(Routes.SETTINGS)
        compose.onNodeWithText("Settings").assertExists()
    }

    @Test
    fun `back from an article returns to home`() {
        val entryId = seedOneEntry(title = "Something to read")
        showNavHost()
        navigateTo(Routes.article(entryId))

        pressBack()

        assertThat(currentRoute()).isEqualTo(Routes.FEED)
    }

    // ---- U09: the bottom bar --------------------------------------------------

    @Test
    fun `the bottom bar is on every list destination and absent on an article`() {
        val entryId = seedOneEntry(title = "Something to read")
        showNavHost()

        compose.onNodeWithTag(NavTestTags.BOTTOM_BAR).assertExists()
        selectTab(PerchTab.ToRead)
        compose.onNodeWithTag(NavTestTags.BOTTOM_BAR).assertExists()
        selectTab(PerchTab.Liked)
        compose.onNodeWithTag(NavTestTags.BOTTOM_BAR).assertExists()

        // The reading surface is the one screen with no furniture under it (§0).
        selectTab(PerchTab.Feed)
        navigateTo(Routes.article(entryId))

        assertThat(currentRoute()).isEqualTo(Routes.ARTICLE)
        compose.onNodeWithTag(NavTestTags.BOTTOM_BAR).assertDoesNotExist()
    }

    @Test
    fun `switching tabs does not stack them`() {
        showNavHost()

        selectTab(PerchTab.ToRead)
        selectTab(PerchTab.Liked)
        selectTab(PerchTab.ToRead)
        selectTab(PerchTab.Liked)

        // `popUpTo(start) { saveState }` + `launchSingleTop`: four switches, and the way
        // out is still one back press. Without them the reader would press back four times.
        pressBack()
        assertThat(currentRoute()).isEqualTo(Routes.FEED)
    }

    /**
     * §0: each tab keeps its own scroll position and its own state across switches.
     *
     * Asserted as the reader would see it — the row they had scrolled to is still the row
     * on screen — rather than by reading a `LazyListState` off the shell, which would pass
     * even if the list had been recomposed from the top underneath it.
     */
    @Test
    fun `Feed's scroll position and time range survive a trip to To-Read`() {
        seedManyEntries(count = 40)
        showNavHost()
        awaitFeedLoaded()
        scrollFeedTo(30)
        assertThat(isDisplayed("Entry 00")).isFalse()
        assertThat(isDisplayed("Entry 30")).isTrue()

        selectTab(PerchTab.ToRead)
        selectTab(PerchTab.Feed)

        assertThat(isDisplayed("Entry 30")).isTrue()
        assertThat(isDisplayed("Entry 00")).isFalse()
        // The range is Feed's alone (§0) and it is where the reader left it.
        compose.onNodeWithTag(HomeTestTags.TIME_RANGE_LABEL, useUnmergedTree = true)
            .assertTextEquals("All Time")
    }

    /**
     * Scrolls the Feed to [index], loading pages on the way (U07a).
     *
     * A paged list only holds what the reader has reached, so an index past the loaded
     * rows is genuinely out of bounds until they get near it — walking there a page at a
     * time is what a thumb does, and it is also the only way this test can reach row 30 of
     * a list whose first page is 30 rows long.
     */
    private fun scrollFeedTo(index: Int) {
        var at = 0
        while (at < index) {
            at = minOf(at + PerchPaging.PAGE_SIZE - 1, index)
            val row = at
            compose.awaitInRealTime("row $row to page in") {
                runCatching {
                    compose.onNodeWithTag(HomeTestTags.ENTRY_LIST).performScrollToIndex(row)
                }.isSuccess
            }
            compose.waitForIdle()
        }
    }

    // ---- U09: §0's back chain, walked end to end -------------------------------

    @Test
    fun `back from To-Read returns to Feed rather than leaving`() {
        showNavHost()
        selectTab(PerchTab.ToRead)

        pressBack()

        assertThat(currentRoute()).isEqualTo(Routes.FEED)
        assertThat(compose.activity.isFinishing).isFalse()
    }

    @Test
    fun `back from Liked returns to Feed rather than leaving`() {
        showNavHost()
        selectTab(PerchTab.Liked)

        pressBack()

        assertThat(currentRoute()).isEqualTo(Routes.FEED)
        assertThat(compose.activity.isFinishing).isFalse()
    }

    @Test
    fun `back on a scrolled Feed scrolls to the top and stays in the app`() {
        seedManyEntries(count = 40)
        showNavHost()
        awaitFeedLoaded()
        scrollFeedTo(30)

        pressBack()

        compose.awaitInRealTime("the list to reach the top") { isDisplayed("Entry 00") }
        assertThat(currentRoute()).isEqualTo(Routes.FEED)
        assertThat(compose.activity.isFinishing).isFalse()
    }

    /** The one rung that may leave, and the only one. */
    @Test
    fun `back on Feed at the top leaves the app`() {
        seedManyEntries(count = 40)
        showNavHost()
        awaitFeedLoaded()

        pressBack()

        assertThat(compose.activity.isFinishing).isTrue()
    }

    private fun selectTab(tab: PerchTab) {
        compose.onNodeWithTag(NavTestTags.tab(tab)).performClick()
        compose.waitForIdle()
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun isDisplayed(text: String): Boolean =
        compose.onAllNodesWithText(text).fetchSemanticsNodes()
            .any { it.layoutInfo.isPlaced && it.size.height > 0 }

    private fun showNavHost() {
        compose.setContent {
            PerchTheme(dynamicColor = false) {
                navController = rememberNavController()
                PerchNavHost(container = container, navController = navController)
            }
        }
        compose.waitForIdle()
    }

    private fun navigateTo(route: String) {
        compose.runOnUiThread { navController.navigate(route) }
        compose.waitForIdle()
    }

    private fun currentRoute(): String? = navController.currentDestination?.route

    /**
     * Waits for [text] in wall-clock time.
     *
     * Arriving at the article route is synchronous; the article is not — `ArticleViewModel`
     * reads the entry on Room's executor, so the first composition after `navigate` draws
     * an empty screen. `compose.waitUntil` cannot be used to wait for it: it advances only
     * the *virtual* clock, so it spins without ever letting that executor run.
     */
    private fun awaitText(text: String) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out waiting for \"$text\"")
    }

    /** Returns the new entry's id. */
    private fun seedOneEntry(title: String): Long = runBlocking {
        database.entryDao().insert(entry(insertFeed(), index = 0, title = title))
    }

    private suspend fun insertFeed(): Long = database.feedDao().insert(
        FeedEntity(
            feedUrl = "https://example.com/feed.xml",
            siteUrl = "https://example.com",
            title = "Example",
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

    private fun entry(feedId: Long, index: Int, title: String) = EntryEntity(
        feedId = feedId,
        guid = "guid-$index",
        title = title,
        link = "https://example.com/post/$index",
        author = null,
        // Descending, so "Entry 00" is newest and therefore first in the list.
        publishedAt = 1_700_000_000_000L - index * 1_000L,
        publishedIsEstimated = false,
        summary = "A short summary.",
        contentHtml = "<p>A short summary.</p>",
        imageUrl = null,
        readAt = null,
        fetchedAt = 1_700_000_000_000L,
    )

    /** [count] entries on one source, titled so a test can name the row it scrolled to. */
    private fun seedManyEntries(count: Int) = runBlocking {
        val feedId = insertFeed()
        repeat(count) { index ->
            database.entryDao().insert(entry(feedId, index, "Entry %02d".format(index)))
        }
    }

    /** Room's first emission is off the main thread, so the list is empty for a beat. */
    private fun awaitFeedLoaded() =
        compose.awaitInRealTime("the feed to load") { isDisplayed("Entry 00") }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 25L
    }
}
