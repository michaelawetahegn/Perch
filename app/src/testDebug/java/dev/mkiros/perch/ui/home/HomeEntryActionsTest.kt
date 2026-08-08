package dev.mkiros.perch.ui.home

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.data.settings.SettingsStore
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.ui.screenshot.awaitInRealTime
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
import org.robolectric.annotation.Config

/**
 * U09's row actions, from the Feed: long-press a row and the sheet's four items each do
 * the one thing they say.
 *
 * Every assertion is against the **database**, not against the screen. The sheet's job is
 * to flip a column; whether the row then leaves the unread list or appears in To-Read is a
 * question for the query, and asserting on the UI here would be asserting on the query
 * twice while asserting on the action not at all.
 *
 * Taps go through `performSemanticsAction` rather than `performClick` for T22's reason: an
 * injected tap does not reach a node inside a sheet, which is its own window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class HomeEntryActionsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: PerchDatabase
    private lateinit var container: AppContainer
    private lateinit var viewModel: HomeViewModel

    private val now = Instant.parse("2026-08-07T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    /**
     * The seeded entries are days old and home opens on Today (U07); and one of these
     * tests marks an already-read entry unread, which it can only do to a row it can see —
     * so the list has to be the "show read entries" one rather than the unread inbox.
     */
    private val settings = SettingsStore.inMemory().also {
        runBlocking {
            it.setTimeFilter(TimeFilter.AllTime)
            it.setShowReadEntries(true)
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = PerchDatabase.inMemory(context)
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

    @Test
    fun `save for later files the entry on the To-Read queue`() {
        val entryId = seed(title = "An Async Runtime in C")
        showHome()

        openActions()
        tap(EntryActionTestTags.SAVE)

        awaitEntry("the entry to be saved") { it.isSaved }
        assertThat(entry().savedAt).isEqualTo(now.toEpochMilli())
    }

    @Test
    fun `a saved entry offers to come off the queue, and does`() {
        val entryId = seed(title = "An Async Runtime in C", isSaved = true)
        showHome()

        openActions()
        // The verb names the direction it is about to go, not the state it is in.
        compose.onNodeWithText("Remove from To-Read").assertExists()
        tap(EntryActionTestTags.SAVE)

        awaitEntry("the entry to leave the queue") { !it.isSaved }
        // The timestamp is the flag's evidence; a cleared flag must not keep it (U04).
        assertThat(entry().savedAt).isNull()
        assertThat(entryId).isEqualTo(entry().id)
    }

    @Test
    fun `like sets the starred column and its timestamp`() {
        seed(title = "An Async Runtime in C")
        showHome()

        openActions()
        tap(EntryActionTestTags.LIKE)

        awaitEntry("the entry to be liked") { it.isStarred }
        assertThat(entry().starredAt).isEqualTo(now.toEpochMilli())
    }

    /** §0's explicit *Mark unread* — the one that also has to forget `readAt`. */
    @Test
    fun `mark unread clears both the read flag and the moment it was read`() {
        seed(title = "An Async Runtime in C", readAt = now.toEpochMilli())
        showHome()

        openActions()
        compose.onNodeWithText("Mark unread").assertExists()
        tap(EntryActionTestTags.READ)

        awaitEntry("the entry to become unread") { !it.isRead }
        assertThat(entry().readAt).isNull()
    }

    @Test
    fun `an unread entry is offered mark read, and takes it`() {
        seed(title = "An Async Runtime in C")
        showHome()

        openActions()
        compose.onNodeWithText("Mark read").assertExists()
        tap(EntryActionTestTags.READ)

        awaitEntry("the entry to become read") { it.isRead }
        assertThat(entry().readAt).isEqualTo(now.toEpochMilli())
    }

    /** All three flags are independent (§0) — saving must not read, liking must not save. */
    @Test
    fun `the three flags do not touch each other`() {
        seed(title = "An Async Runtime in C")
        showHome()

        openActions()
        tap(EntryActionTestTags.SAVE)
        awaitEntry("the entry to be saved") { it.isSaved }

        val saved = entry()
        assertThat(saved.isStarred).isFalse()
        assertThat(saved.isRead).isFalse()
    }

    // ---- harness ---------------------------------------------------------------

    private fun openActions() {
        compose.onNodeWithTag(HomeTestTags.ENTRY)
            .performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.onNodeWithTag(EntryActionTestTags.SHEET).assertExists()
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private var entryId: Long = 0L

    private fun entry(): EntryEntity = runBlocking { database.entryDao().findById(entryId)!! }

    /** The write goes through a coroutine and Room's executor — wall-clock, never virtual. */
    private fun awaitEntry(what: String, predicate: (EntryEntity) -> Boolean) =
        compose.awaitInRealTime(what) { predicate(entry()) }

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
        compose.awaitInRealTime("the list to load") { viewModel.uiState.value.entries.isNotEmpty() }
    }

    private fun seed(
        title: String,
        isSaved: Boolean = false,
        readAt: Long? = null,
    ): Long = runBlocking {
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
        entryId = database.entryDao().insert(
            EntryEntity(
                feedId = feedId,
                guid = "guid-1",
                title = title,
                link = "https://example.com/post",
                author = null,
                publishedAt = now.minusSeconds(2 * DAY).toEpochMilli(),
                publishedIsEstimated = false,
                summary = "A short summary.",
                contentHtml = "<p>A short summary.</p>",
                imageUrl = null,
                isRead = readAt != null,
                readAt = readAt,
                isSaved = isSaved,
                savedAt = if (isSaved) 0L else null,
                fetchedAt = now.toEpochMilli(),
            ),
        )
        entryId
    }

    private companion object {
        const val DAY = 24 * 3_600L
    }
}
