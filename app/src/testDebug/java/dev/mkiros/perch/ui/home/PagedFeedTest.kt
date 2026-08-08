package dev.mkiros.perch.ui.home

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.data.repo.PerchPaging
import dev.mkiros.perch.data.settings.SettingsStore
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.ui.rowTitles
import dev.mkiros.perch.ui.screenshot.awaitInRealTime
import dev.mkiros.perch.ui.source.AddSourceViewModel
import dev.mkiros.perch.ui.theme.PerchTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * What paging looks like from the reader's chair (U07a).
 *
 * The rule the task is written around is that **the reader should never learn that paging
 * exists**, so the assertions here are about the two moments where it would otherwise show
 * itself: the bottom of a long list, which must end rather than spin forever, and a write
 * landing while the reader is somewhere in the middle, which must not throw them back to
 * the top.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class PagedFeedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: PerchDatabase
    private lateinit var container: AppContainer
    private lateinit var viewModel: HomeViewModel

    private val now = Instant.parse("2026-08-08T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    /** Home opens on Today (U07); these entries are seeded across a year on purpose. */
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

    // ---- where the list ends ----------------------------------------------------

    /**
     * The footer is the one place paging is allowed to be visible, and what it says there
     * has to be true: a list that has no more pages says so and stops. An indicator left
     * spinning at the bottom of a finished list is a promise the list cannot keep.
     */
    @Test
    fun `a list that has run out of pages ends with a marker, not a spinner`() {
        seed(count = PerchPaging.PAGE_SIZE + 5)
        showHome()

        scrollToBottom()

        compose.onNodeWithTag(PagedListTestTags.END).assertIsDisplayed()
        assertThat(exists(PagedListTestTags.APPENDING)).isFalse()
    }

    /**
     * A list that never paged does not need to explain where it ends — it ends where it
     * stops, visibly. Stamping "that's everything" under four rows would be teaching the
     * reader about a mechanism they have not met.
     */
    @Test
    fun `a list shorter than a page is not stamped with an end marker`() {
        seed(count = 4)
        showHome()

        compose.awaitInRealTime("the list") { isDisplayed("Entry 00") }

        assertThat(exists(PagedListTestTags.END)).isFalse()
        assertThat(exists(PagedListTestTags.APPENDING)).isFalse()
    }

    // ---- what a write does to where the reader is standing -----------------------

    /**
     * Marking one entry read invalidates the paged list, which is a reload — and a reload
     * that came back anchored at the top would take the reader with it. Paging re-reads
     * around where they are instead, so the row under their thumb stays under their thumb.
     */
    @Test
    fun `a read-state change leaves the reader where they were, not at the top`() {
        seed(count = 60)
        showHome()
        scrollTo(40)
        assertThat(isDisplayed("Entry 00")).isFalse()

        runBlocking { container.entries.setRead(idOf("Entry 00"), isRead = true) }
        compose.awaitInRealTime("the list to take the write") {
            !compose.rowTitles().contains("Entry 00")
        }

        assertThat(isDisplayed("Entry 40")).isTrue()
        assertThat(isDisplayed("Entry 00")).isFalse()
    }

    // ---- harness -----------------------------------------------------------------

    private fun exists(tag: String): Boolean =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun isDisplayed(text: String): Boolean =
        compose.onAllNodesWithText(text).fetchSemanticsNodes()
            .any { it.layoutInfo.isPlaced && it.size.height > 0 }

    /**
     * Scrolls to [index], letting pages load on the way — an index past the loaded rows is
     * genuinely out of bounds until the reader gets near it, which is the point.
     */
    private fun scrollTo(index: Int) {
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

    /**
     * Walks to the footer a row at a time, however many pages that takes.
     *
     * A row at a time rather than a page at a time because the list's length is not known
     * up front — that is what paging means — so the only safe next index is the next one,
     * and an index the list has not reached yet simply fails and is retried.
     */
    private fun scrollToBottom() {
        var row = 0
        compose.awaitInRealTime("the end of the list") {
            val moved = runCatching {
                compose.onNodeWithTag(HomeTestTags.ENTRY_LIST).performScrollToIndex(row)
            }.isSuccess
            if (moved) row++
            compose.waitForIdle()
            exists(PagedListTestTags.END)
        }
    }

    private fun showHome() {
        viewModel = HomeViewModel(
            entries = container.entries,
            feeds = container.feeds,
            folders = container.folders,
            clock = clock,
            settings = settings,
        )
        compose.setContent {
            PerchTheme(dynamicColor = false) {
                HomeScreen(
                    viewModel = viewModel,
                    addSourceViewModel = AddSourceViewModel(container.feeds, container.folders),
                    onOpenEntry = {},
                    onOpenSettings = {},
                )
            }
        }
        compose.awaitInRealTime("the first page") { isDisplayed("Entry 00") }
    }

    private fun idOf(title: String): Long = runBlocking {
        database.entryDao().observeAll().first().first { it.title == title }.id
    }

    private fun seed(count: Int) = runBlocking {
        val feedId = database.feedDao().insert(
            FeedEntity(
                feedUrl = "https://example.com/feed.xml",
                siteUrl = "https://example.com",
                title = "Null Program",
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
        repeat(count) { index ->
            database.entryDao().insert(
                EntryEntity(
                    feedId = feedId,
                    guid = "guid-$index",
                    title = "Entry %02d".format(index),
                    link = "https://example.com/$index",
                    author = null,
                    publishedAt = now.toEpochMilli() - index * 60_000L,
                    publishedIsEstimated = false,
                    summary = null,
                    contentHtml = null,
                    imageUrl = null,
                    readAt = null,
                    fetchedAt = now.toEpochMilli(),
                ),
            )
        }
    }
}
