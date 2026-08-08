package dev.mkiros.perch.ui.collection

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
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
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.ui.home.EntryActionTestTags
import dev.mkiros.perch.ui.screenshot.awaitInRealTime
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
 * *To-Read* and *Liked* (U09): what they contain, in what order, what they say when they
 * are empty, and what happens when the reader takes something out of one.
 *
 * The two lists exist to hold what the reader chose, so the properties worth defending are
 * the ones the Feed does not have — a **read** entry stays put, the order is the reader's
 * own gesture rather than publication, and a removal is undoable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class CollectionScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: PerchDatabase
    private lateinit var container: AppContainer
    private lateinit var viewModel: CollectionViewModel

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

    // ---- the empty states ------------------------------------------------------

    @Test
    fun `an empty To-Read says what the list is for, not that it is empty`() {
        show(Collection.ToRead)

        compose.onNodeWithTag(CollectionTestTags.EMPTY).assertExists()
        compose.onNodeWithText("Nothing queued yet").assertIsDisplayed()
        compose.onNodeWithText(
            "Long-press an entry and save it for later. It waits here until you take it " +
                "off, however long that is.",
        ).assertIsDisplayed()
    }

    @Test
    fun `an empty Liked says what liking is for`() {
        show(Collection.Liked)

        compose.onNodeWithTag(CollectionTestTags.EMPTY).assertExists()
        compose.onNodeWithText("Nothing liked yet").assertIsDisplayed()
    }

    // ---- what the lists hold ---------------------------------------------------

    @Test
    fun `To-Read is ordered by when the reader saved it, not by publication`() {
        val feedId = seedFeed()
        // Published oldest-last, saved newest-last: the two orders disagree on purpose.
        seedEntry(feedId, "Older post", publishedAt = now.minusSeconds(9 * DAY), savedAt = now)
        seedEntry(
            feedId,
            "Newer post",
            publishedAt = now.minusSeconds(DAY),
            savedAt = now.minusSeconds(HOUR),
        )

        show(Collection.ToRead)

        assertThat(titles()).containsExactly("Older post", "Newer post").inOrder()
    }

    @Test
    fun `Liked is ordered by when the reader liked it`() {
        val feedId = seedFeed()
        seedEntry(feedId, "Liked first", publishedAt = now, starredAt = now.minusSeconds(HOUR))
        seedEntry(feedId, "Liked last", publishedAt = now.minusSeconds(DAY), starredAt = now)

        show(Collection.Liked)

        assertThat(titles()).containsExactly("Liked last", "Liked first").inOrder()
    }

    /** Reading something you saved is not the same as being done with it (§0). */
    @Test
    fun `a read entry stays on the To-Read queue`() {
        val feedId = seedFeed()
        seedEntry(feedId, "Already read", savedAt = now, readAt = now.toEpochMilli())

        show(Collection.ToRead)

        compose.onNodeWithText("Already read").assertIsDisplayed()
    }

    /** The time filter is Feed's alone (§0): a queue that hides last month is not a queue. */
    @Test
    fun `an entry published a year ago is still on the queue`() {
        val feedId = seedFeed()
        seedEntry(feedId, "From last year", publishedAt = now.minusSeconds(400 * DAY), savedAt = now)

        show(Collection.ToRead)

        compose.onNodeWithText("From last year").assertIsDisplayed()
    }

    /**
     * The list is a Room `Flow`, so an entry saved from *anywhere* — the Feed's sheet, the
     * article's toggle, a restore — arrives here on its own. Nothing re-queries, and
     * nothing has to know this screen exists.
     */
    @Test
    fun `an entry saved from elsewhere arrives without this screen asking`() {
        val feedId = seedFeed()
        val entryId = seedEntry(feedId, "Saved from the Feed")
        show(Collection.ToRead)
        compose.onNodeWithTag(CollectionTestTags.EMPTY).assertExists()

        runBlocking { container.entries.setSaved(entryId, isSaved = true) }

        compose.awaitInRealTime("the queue to fill") { titles().contains("Saved from the Feed") }
        compose.onNodeWithText("Saved from the Feed").assertIsDisplayed()
    }

    // ---- taking something out --------------------------------------------------

    @Test
    fun `un-saving from To-Read takes the row out and offers to put it back`() {
        val feedId = seedFeed()
        val entryId = seedEntry(feedId, "An Async Runtime in C", savedAt = now)
        show(Collection.ToRead)

        openActions()
        tap(EntryActionTestTags.SAVE)

        compose.awaitInRealTime("the row to leave") { entry(entryId).isSaved.not() }
        assertThat(entry(entryId).savedAt).isNull()
        compose.awaitInRealTime("the undo offer") { viewModel.pendingUndo.value != null }
        compose.onNodeWithText("Removed from To-Read").assertExists()
    }

    @Test
    fun `undo puts the entry back on the queue`() {
        val feedId = seedFeed()
        val entryId = seedEntry(feedId, "An Async Runtime in C", savedAt = now)
        show(Collection.ToRead)
        openActions()
        tap(EntryActionTestTags.SAVE)
        compose.awaitInRealTime("the undo offer") { viewModel.pendingUndo.value != null }

        compose.onNodeWithText("Undo").performSemanticsAction(SemanticsActions.OnClick)

        compose.awaitInRealTime("the entry to return") { entry(entryId).isSaved }
        compose.awaitInRealTime("the row to return") { titles().contains("An Async Runtime in C") }
    }

    /**
     * Liking from *To-Read* files it under Liked and leaves the queue alone — the flags are
     * independent, and this is the screen where confusing them would be least visible.
     */
    @Test
    fun `liking from To-Read does not take the entry off the queue`() {
        val feedId = seedFeed()
        val entryId = seedEntry(feedId, "An Async Runtime in C", savedAt = now)
        show(Collection.ToRead)

        openActions()
        tap(EntryActionTestTags.LIKE)

        compose.awaitInRealTime("the entry to be liked") { entry(entryId).isStarred }
        assertThat(entry(entryId).isSaved).isTrue()
        // Nothing left the list, so nothing is offered back.
        assertThat(viewModel.pendingUndo.value).isNull()
    }

    // ---- harness ---------------------------------------------------------------

    private fun titles(): List<String> = viewModel.uiState.value.entries.map { it.title }

    private fun entry(id: Long): EntryEntity = runBlocking { database.entryDao().findById(id)!! }

    private fun openActions() {
        compose.onNodeWithTag(CollectionTestTags.ENTRY)
            .performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.onNodeWithTag(EntryActionTestTags.SHEET).assertExists()
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun show(collection: Collection) {
        viewModel = CollectionViewModel(container.entries, clock, collection)
        compose.setContent {
            PerchTheme(dynamicColor = false) {
                CollectionScreen(viewModel = viewModel, onOpenEntry = {})
            }
        }
        compose.awaitInRealTime("the list to load") { !viewModel.uiState.value.isLoading }
    }

    private fun seedFeed(): Long = runBlocking {
        database.feedDao().insert(
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
    }

    private fun seedEntry(
        feedId: Long,
        title: String,
        publishedAt: Instant = now.minusSeconds(2 * DAY),
        savedAt: Instant? = null,
        starredAt: Instant? = null,
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
                summary = "A short summary.",
                contentHtml = "<p>A short summary.</p>",
                imageUrl = null,
                isRead = readAt != null,
                readAt = readAt,
                isSaved = savedAt != null,
                savedAt = savedAt?.toEpochMilli(),
                isStarred = starredAt != null,
                starredAt = starredAt?.toEpochMilli(),
                fetchedAt = now.toEpochMilli(),
            ),
        )
    }

    private companion object {
        const val HOUR = 3_600L
        const val DAY = 24 * HOUR
    }
}
