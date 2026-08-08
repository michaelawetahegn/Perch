package dev.mkiros.perch.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The storage contract (SPEC.md §4): identity, reactivity, and what survives a
 * refetch. Everything here runs against a real in-memory SQLite through Room, so a
 * broken index or a missing `ON DELETE CASCADE` fails here rather than on a device.
 */
@RunWith(RobolectricTestRunner::class)
class PerchDatabaseTest {

    private lateinit var db: PerchDatabase
    private lateinit var feeds: FeedDao
    private lateinit var entries: EntryDao

    @Before
    fun openDatabase() {
        db = PerchDatabase.inMemory(ApplicationProvider.getApplicationContext())
        feeds = db.feedDao()
        entries = db.entryDao()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    // ---- feeds ----------------------------------------------------------------

    @Test
    fun `an inserted feed is retrievable by its url`() = runTest {
        val id = feeds.insert(feed(feedUrl = "https://example.com/feed.xml"))

        val found = feeds.findByUrl("https://example.com/feed.xml")

        assertThat(id).isGreaterThan(0L)
        assertThat(found?.id).isEqualTo(id)
        assertThat(found?.title).isEqualTo("Example")
    }

    @Test
    fun `the same feed url cannot be added twice`() = runTest {
        feeds.insert(feed(feedUrl = "https://example.com/feed.xml"))

        try {
            feeds.insert(feed(feedUrl = "https://example.com/feed.xml", title = "Copy"))
            throw AssertionError("expected the unique feedUrl index to reject the duplicate")
        } catch (expected: SQLiteConstraintException) {
            assertThat(expected).hasMessageThat().contains("feeds.feedUrl")
        }
    }

    @Test
    fun `feeds are observed in sort order and the flow re-emits on insert`() = runTest {
        feeds.insert(feed(feedUrl = "https://b.example/feed", title = "Bravo", sortIndex = 1))
        feeds.insert(feed(feedUrl = "https://a.example/feed", title = "Alpha", sortIndex = 0))

        feeds.observeAll().test {
            assertThat(awaitItem().map { it.title }).containsExactly("Alpha", "Bravo").inOrder()

            feeds.insert(feed(feedUrl = "https://c.example/feed", title = "Charlie", sortIndex = 2))

            assertThat(awaitItem().map { it.title })
                .containsExactly("Alpha", "Bravo", "Charlie").inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a renamed feed keeps its parsed title`() = runTest {
        val id = feeds.insert(feed(feedUrl = "https://example.com/feed.xml", title = "Example"))

        feeds.setCustomTitle(id, "My favourite")

        val found = feeds.findById(id)
        assertThat(found?.title).isEqualTo("Example")
        assertThat(found?.customTitle).isEqualTo("My favourite")
    }

    // ---- entries --------------------------------------------------------------

    @Test
    fun `entries are listed newest first across all feeds`() = runTest {
        val a = feeds.insert(feed(feedUrl = "https://a.example/feed", title = "Alpha"))
        val b = feeds.insert(feed(feedUrl = "https://b.example/feed", title = "Bravo"))
        entries.upsertAll(
            listOf(
                entry(a, guid = "a1", title = "oldest", publishedAt = 1_000L),
                entry(b, guid = "b1", title = "newest", publishedAt = 3_000L),
                entry(a, guid = "a2", title = "middle", publishedAt = 2_000L),
            ),
        )

        entries.observeAll().test {
            assertThat(awaitItem().map { it.title })
                .containsExactly("newest", "middle", "oldest").inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `filtering by source lists only that source's entries`() = runTest {
        val a = feeds.insert(feed(feedUrl = "https://a.example/feed", title = "Alpha"))
        val b = feeds.insert(feed(feedUrl = "https://b.example/feed", title = "Bravo"))
        entries.upsertAll(
            listOf(
                entry(a, guid = "a1", title = "from alpha"),
                entry(b, guid = "b1", title = "from bravo"),
            ),
        )

        entries.observeByFeed(b).test {
            assertThat(awaitItem().map { it.title }).containsExactly("from bravo")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `two feeds may publish the same guid`() = runTest {
        val a = feeds.insert(feed(feedUrl = "https://a.example/feed"))
        val b = feeds.insert(feed(feedUrl = "https://b.example/feed"))

        val inserted = entries.upsertAll(
            listOf(entry(a, guid = "shared"), entry(b, guid = "shared")),
        )

        assertThat(inserted).isEqualTo(2)
        assertThat(entries.countAll()).isEqualTo(2)
    }

    @Test
    fun `re-upserting the same guid updates in place instead of crashing`() = runTest {
        val id = feeds.insert(feed(feedUrl = "https://a.example/feed"))
        entries.upsertAll(listOf(entry(id, guid = "g1", title = "draft", summary = "old")))

        val inserted = entries.upsertAll(
            listOf(entry(id, guid = "g1", title = "corrected", summary = "new")),
        )

        assertThat(inserted).isEqualTo(0)
        assertThat(entries.countAll()).isEqualTo(1)
        val stored = entries.findByGuid(id, "g1")
        assertThat(stored?.title).isEqualTo("corrected")
        assertThat(stored?.summary).isEqualTo("new")
    }

    @Test
    fun `re-upserting an entry preserves the read state the user set`() = runTest {
        val id = feeds.insert(feed(feedUrl = "https://a.example/feed"))
        entries.upsertAll(listOf(entry(id, guid = "g1", title = "draft")))
        val stored = entries.findByGuid(id, "g1")!!
        entries.update(stored.copy(isRead = true, readAt = 4_242L, isStarred = true))

        entries.upsertAll(listOf(entry(id, guid = "g1", title = "corrected")))

        val after = entries.findByGuid(id, "g1")
        assertThat(after?.id).isEqualTo(stored.id)
        assertThat(after?.title).isEqualTo("corrected")
        assertThat(after?.isRead).isTrue()
        assertThat(after?.readAt).isEqualTo(4_242L)
        assertThat(after?.isStarred).isTrue()
    }

    @Test
    fun `removing a feed removes its entries and leaves the others alone`() = runTest {
        val doomed = feeds.insert(feed(feedUrl = "https://a.example/feed"))
        val kept = feeds.insert(feed(feedUrl = "https://b.example/feed"))
        entries.upsertAll(
            listOf(entry(doomed, guid = "a1"), entry(doomed, guid = "a2"), entry(kept, guid = "b1")),
        )

        feeds.deleteById(doomed)

        assertThat(entries.countAll()).isEqualTo(1)
        assertThat(entries.findByGuid(kept, "b1")).isNotNull()
    }

    @Test
    fun `an entry cannot belong to a feed that does not exist`() = runTest {
        try {
            entries.upsertAll(listOf(entry(feedId = 999L, guid = "orphan")))
            throw AssertionError("expected the feedId foreign key to reject the orphan")
        } catch (expected: SQLiteConstraintException) {
            assertThat(expected).hasMessageThat().contains("FOREIGN KEY")
        }
    }

    // ---- fixtures -------------------------------------------------------------

    private fun feed(
        feedUrl: String,
        title: String = "Example",
        sortIndex: Int = 0,
    ) = FeedEntity(
        feedUrl = feedUrl,
        siteUrl = "https://example.com/",
        title = title,
        customTitle = null,
        faviconUrl = null,
        etag = null,
        lastModified = null,
        lastFetchedAt = null,
        lastSuccessAt = null,
        lastError = null,
        addedAt = 1_700_000_000_000L,
        sortIndex = sortIndex,
    )

    private fun entry(
        feedId: Long,
        guid: String,
        title: String = "An entry",
        publishedAt: Long = 1_700_000_000_000L,
        summary: String? = null,
    ) = EntryEntity(
        feedId = feedId,
        guid = guid,
        title = title,
        link = "https://example.com/$guid",
        author = null,
        publishedAt = publishedAt,
        publishedIsEstimated = false,
        summary = summary,
        contentHtml = null,
        imageUrl = null,
        readAt = null,
        fetchedAt = 1_700_000_000_000L,
    )
}
