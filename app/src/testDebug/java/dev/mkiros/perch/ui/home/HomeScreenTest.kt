package dev.mkiros.perch.ui.home

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.di.AppContainer
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
 * The unified unread list (T21): what a row says, what order rows come in, and what the
 * screen says when there is nothing to show.
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

    private val now = Instant.parse("2026-08-07T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PerchDatabase::class.java)
            .allowMainThreadQueries()
            .build()
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

    // ---- harness ---------------------------------------------------------------

    private fun showHome(onOpenEntry: (Long) -> Unit = {}) {
        val viewModel = HomeViewModel(
            entries = container.entries,
            feeds = container.feeds,
            clock = clock,
        )
        compose.setContent {
            PerchTheme(dynamicColor = false) {
                HomeScreen(
                    viewModel = viewModel,
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

    private fun seedFeed(title: String, customTitle: String? = null): Long = runBlocking {
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
                lastError = null,
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
    }
}
