package dev.mkiros.perch.data.repo

import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.EntryDao
import dev.mkiros.perch.data.db.FeedDao
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The read-state contract (SPEC.md §8): what "read" means, what "mark all read" is
 * scoped to, and what undo puts back.
 *
 * Counts are asserted through Turbine because the requirement is not that a count is
 * *correct* when asked — it is that the list and the drawer badge move on their own when
 * the user reads something. A `suspend fun count()` would satisfy the arithmetic and fail
 * the product.
 */
@RunWith(RobolectricTestRunner::class)
class EntryRepositoryTest {

    private lateinit var db: PerchDatabase
    private lateinit var feeds: FeedDao
    private lateinit var entries: EntryDao
    private lateinit var repo: EntryRepository

    /** `readAt` must be stamped from an injected clock, not `System.currentTimeMillis()`. */
    private val now = 1_700_000_500_000L

    @Before
    fun openDatabase() {
        db = PerchDatabase.inMemory(ApplicationProvider.getApplicationContext())
        feeds = db.feedDao()
        entries = db.entryDao()
        repo = EntryRepository(
            entryDao = entries,
            clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
        )
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    // ---- marking a single entry ------------------------------------------------

    @Test
    fun `opening an entry marks it read and stamps when`() = runTest {
        val feed = feeds.insert(feed("https://a.example/feed"))
        val id = insertEntry(feed, "a1")

        repo.setRead(id, isRead = true)

        val stored = entries.findById(id)
        assertThat(stored?.isRead).isTrue()
        assertThat(stored?.readAt).isEqualTo(now)
    }

    @Test
    fun `toggling a read entry makes it unread again and forgets when`() = runTest {
        val feed = feeds.insert(feed("https://a.example/feed"))
        val id = insertEntry(feed, "a1")
        repo.setRead(id, isRead = true)

        repo.toggleRead(id)

        val stored = entries.findById(id)
        assertThat(stored?.isRead).isFalse()
        assertThat(stored?.readAt).isNull()
    }

    @Test
    fun `toggling an unread entry marks it read`() = runTest {
        val feed = feeds.insert(feed("https://a.example/feed"))
        val id = insertEntry(feed, "a1")

        repo.toggleRead(id)

        assertThat(entries.findById(id)?.isRead).isTrue()
    }

    // ---- counts ----------------------------------------------------------------

    @Test
    fun `the total unread count falls as entries are read`() = runTest {
        val feed = feeds.insert(feed("https://a.example/feed"))
        val first = insertEntry(feed, "a1")
        insertEntry(feed, "a2")

        repo.observeTotalUnreadCount().test {
            assertThat(awaitItem()).isEqualTo(2)

            repo.setRead(first, isRead = true)

            assertThat(awaitItem()).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the total unread count rises when a refresh brings in new entries`() = runTest {
        val feed = feeds.insert(feed("https://a.example/feed"))
        insertEntry(feed, "a1")

        repo.observeTotalUnreadCount().test {
            assertThat(awaitItem()).isEqualTo(1)

            insertEntry(feed, "a2")

            assertThat(awaitItem()).isEqualTo(2)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unread counts are reported per source and update reactively`() = runTest {
        val a = feeds.insert(feed("https://a.example/feed"))
        val b = feeds.insert(feed("https://b.example/feed"))
        val a1 = insertEntry(a, "a1")
        insertEntry(a, "a2")
        insertEntry(b, "b1")

        repo.observeUnreadCountsByFeed().test {
            assertThat(awaitItem()).containsExactly(a, 2, b, 1)

            repo.setRead(a1, isRead = true)

            assertThat(awaitItem()).containsExactly(a, 1, b, 1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * `GROUP BY` cannot emit a row for a feed with nothing unread, so a fully-read source
     * is *absent* from the map rather than mapped to 0. The drawer must read it as
     * `counts[feedId] ?: 0`; pinned here so nobody discovers it as a missing badge.
     */
    @Test
    fun `a fully read source drops out of the counts map`() = runTest {
        val a = feeds.insert(feed("https://a.example/feed"))
        val b = feeds.insert(feed("https://b.example/feed"))
        val a1 = insertEntry(a, "a1")
        insertEntry(b, "b1")

        repo.setRead(a1, isRead = true)

        repo.observeUnreadCountsByFeed().test {
            assertThat(awaitItem()).containsExactly(b, 1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- mark all read ---------------------------------------------------------

    @Test
    fun `mark all read in one source leaves the other sources alone`() = runTest {
        val a = feeds.insert(feed("https://a.example/feed"))
        val b = feeds.insert(feed("https://b.example/feed"))
        insertEntry(a, "a1")
        insertEntry(a, "a2")
        val b1 = insertEntry(b, "b1")

        repo.markAllRead(feedId = a)

        assertThat(unreadIds()).containsExactly(b1)
    }

    @Test
    fun `mark all read without a scope reads every source`() = runTest {
        val a = feeds.insert(feed("https://a.example/feed"))
        val b = feeds.insert(feed("https://b.example/feed"))
        insertEntry(a, "a1")
        insertEntry(b, "b1")

        repo.markAllRead(feedId = null)

        assertThat(unreadIds()).isEmpty()
    }

    @Test
    fun `mark all read stamps every entry it flipped`() = runTest {
        val a = feeds.insert(feed("https://a.example/feed"))
        val id = insertEntry(a, "a1")

        repo.markAllRead(feedId = a)

        assertThat(entries.findById(id)?.readAt).isEqualTo(now)
    }

    @Test
    fun `undo restores exactly the entries mark all read flipped`() = runTest {
        val a = feeds.insert(feed("https://a.example/feed"))
        val alreadyRead = insertEntry(a, "a1")
        val flippedOne = insertEntry(a, "a2")
        val flippedTwo = insertEntry(a, "a3")
        repo.setRead(alreadyRead, isRead = true)

        val undo = repo.markAllRead(feedId = a)
        repo.undoMarkAllRead(undo)

        // The entry the user had already read stays read: it was never part of the batch.
        assertThat(unreadIds()).containsExactly(flippedOne, flippedTwo)
        assertThat(entries.findById(alreadyRead)?.isRead).isTrue()
        assertThat(entries.findById(flippedOne)?.readAt).isNull()
    }

    @Test
    fun `undo reports how many entries it would restore so the snackbar can say so`() = runTest {
        val a = feeds.insert(feed("https://a.example/feed"))
        insertEntry(a, "a1")
        insertEntry(a, "a2")

        assertThat(repo.markAllRead(feedId = a).count).isEqualTo(2)
    }

    @Test
    fun `undoing a mark all read that flipped nothing is a no-op`() = runTest {
        val a = feeds.insert(feed("https://a.example/feed"))
        val id = insertEntry(a, "a1")
        repo.setRead(id, isRead = true)

        val undo = repo.markAllRead(feedId = a)
        repo.undoMarkAllRead(undo)

        assertThat(undo.count).isEqualTo(0)
        assertThat(entries.findById(id)?.isRead).isTrue()
    }

    @Test
    fun `an entry read after the batch is not resurrected by undo`() = runTest {
        val a = feeds.insert(feed("https://a.example/feed"))
        val b = feeds.insert(feed("https://b.example/feed"))
        insertEntry(a, "a1")
        val b1 = insertEntry(b, "b1")

        val undo = repo.markAllRead(feedId = a)
        repo.setRead(b1, isRead = true)
        repo.undoMarkAllRead(undo)

        assertThat(entries.findById(b1)?.isRead).isTrue()
    }

    /**
     * SQLite binds each id in an `IN (…)` clause as its own host variable and caps them
     * at 999. A busy unified inbox blows past that, so the batch has to be chunked — a
     * bug that only ever shows up on a real corpus.
     */
    @Test
    fun `mark all read handles more entries than SQLite allows bound variables`() = runTest {
        val a = feeds.insert(feed("https://a.example/feed"))
        repeat(1_200) { insertEntry(a, "a$it") }

        val undo = repo.markAllRead(feedId = a)

        assertThat(undo.count).isEqualTo(1_200)
        assertThat(unreadIds()).isEmpty()

        repo.undoMarkAllRead(undo)

        assertThat(unreadIds()).hasSize(1_200)
    }

    // ---- show read entries (T27) -----------------------------------------------

    @Test
    fun `the reading list drops an entry once it is read`() = runTest {
        val a = feeds.insert(feed("https://a.example/feed"))
        insertEntry(a, "a1")
        val a2 = insertEntry(a, "a2")

        repo.setRead(a2, isRead = true)

        assertThat(titles(includeRead = false)).containsExactly("Entry a1")
    }

    @Test
    fun `showing read entries keeps them in the list, still marked read`() = runTest {
        val a = feeds.insert(feed("https://a.example/feed"))
        insertEntry(a, "a1")
        val a2 = insertEntry(a, "a2")
        repo.setRead(a2, isRead = true)

        val listed = repo.observeEntries(includeRead = true).first()

        assertThat(listed.map { it.title }).containsExactly("Entry a1", "Entry a2")
        // The row still has to be able to draw itself differently: "shown" is not "unread".
        assertThat(listed.single { it.title == "Entry a2" }.isRead).isTrue()
    }

    @Test
    fun `showing read entries still respects the per-source filter`() = runTest {
        val a = feeds.insert(feed("https://a.example/feed"))
        val b = feeds.insert(feed("https://b.example/feed"))
        val a1 = insertEntry(a, "a1")
        insertEntry(b, "b1")
        repo.setRead(a1, isRead = true)

        val listed = repo.observeEntries(feedId = a, includeRead = true).first()

        assertThat(listed.map { it.title }).containsExactly("Entry a1")
    }

    // ---- fixtures --------------------------------------------------------------

    private suspend fun titles(includeRead: Boolean): List<String> =
        repo.observeEntries(includeRead = includeRead).first().map { it.title }

    private suspend fun unreadIds(): List<Long> = entries.unreadIds(feedId = null)

    private suspend fun insertEntry(feedId: Long, guid: String): Long = entries.insert(
        EntryEntity(
            feedId = feedId,
            guid = guid,
            title = "Entry $guid",
            link = "https://example.com/$guid",
            author = null,
            publishedAt = 1_700_000_000_000L,
            publishedIsEstimated = false,
            summary = null,
            contentHtml = null,
            imageUrl = null,
            readAt = null,
            fetchedAt = 1_700_000_000_000L,
        ),
    )

    private fun feed(feedUrl: String) = FeedEntity(
        feedUrl = feedUrl,
        siteUrl = "https://example.com/",
        title = "Example",
        customTitle = null,
        faviconUrl = null,
        etag = null,
        lastModified = null,
        lastFetchedAt = null,
        lastSuccessAt = null,
        lastError = null,
        addedAt = 1_700_000_000_000L,
        sortIndex = 0,
    )
}
