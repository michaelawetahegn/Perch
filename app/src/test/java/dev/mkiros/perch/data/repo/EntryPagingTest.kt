package dev.mkiros.perch.data.repo

import androidx.paging.PagingSource
import androidx.paging.testing.asSnapshot
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.EntryDao
import dev.mkiros.perch.data.db.EntryListItem
import dev.mkiros.perch.data.db.FeedDao
import dev.mkiros.perch.data.db.FolderDao
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.db.entity.FolderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * U07a's contract: the lists load a page at a time, and a write costs a page rather than
 * the corpus.
 *
 * The properties defended here are the ones a reader would only notice as slowness, which
 * is why they are asserted as counts rather than as timings — "the first collection
 * produced 30 rows out of a possible 2000" is a fact a test can hold on to, and
 * "the screen felt quick" is not.
 */
@RunWith(RobolectricTestRunner::class)
class EntryPagingTest {

    private lateinit var db: PerchDatabase
    private lateinit var feeds: FeedDao
    private lateinit var folders: FolderDao
    private lateinit var entries: EntryDao
    private lateinit var repo: EntryRepository

    private val page = PerchPaging.PAGE_SIZE

    @Before
    fun openDatabase() {
        // `asSnapshot` drives the differ on the main dispatcher, so it has to be one this
        // test controls rather than Robolectric's looper.
        Dispatchers.setMain(StandardTestDispatcher())
        db = PerchDatabase.inMemory(ApplicationProvider.getApplicationContext())
        feeds = db.feedDao()
        folders = db.folderDao()
        entries = db.entryDao()
        repo = EntryRepository(
            entryDao = entries,
            clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
        )
    }

    @After
    fun closeDatabase() {
        db.close()
        Dispatchers.resetMain()
    }

    // ---- one page, not the inbox -----------------------------------------------

    /**
     * The whole point of the task, stated as the difference between the two APIs over the
     * same 2000 rows: the flow materialises all of them, the pager materialises a page.
     */
    @Test
    fun `the first collection of a 2000-entry inbox loads one page, not the inbox`() = runTest {
        val feed = feeds.insert(feed("https://a.example/feed"))
        seed(feed, count = 2_000)

        val whole = repo.observeEntries(includeRead = true).first()
        val paged = repo.pagedEntries(includeRead = true).asSnapshot()

        assertThat(whole).hasSize(2_000)
        assertThat(paged).hasSize(page)
    }

    /** The two lists that never shed a row (U04) page the same way. */
    @Test
    fun `To-Read and Liked load a page at a time too`() = runTest {
        val feed = feeds.insert(feed("https://a.example/feed"))
        seed(feed, count = 200).forEach { id ->
            entries.setSaved(id, isSaved = true, savedAt = id)
            entries.setStarred(id, isStarred = true, starredAt = id)
        }

        assertThat(repo.pagedSaved().asSnapshot()).hasSize(page)
        assertThat(repo.pagedLiked().asSnapshot()).hasSize(page)
    }

    @Test
    fun `scrolling appends the next page and keeps everything already loaded`() = runTest {
        val feed = feeds.insert(feed("https://a.example/feed"))
        seed(feed, count = 2_000)
        val expected = repo.observeEntries(includeRead = true).first().map { it.id }

        val paged = repo.pagedEntries(includeRead = true).asSnapshot { scrollTo(page) }

        // Appended, not replaced: the rows already on screen are still on screen, at the
        // same indices, which is what "scrolling does not reset position" means to a list.
        assertThat(paged.map { it.id }).isEqualTo(expected.take(paged.size))
        assertThat(paged.size).isAtLeast(2 * page)
        assertThat(paged.size).isLessThan(2_000)
    }

    /**
     * A list shorter than a page still ends, and it ends without a second load — the
     * `PagingSource` says so itself, which is what lets the footer draw a marker rather
     * than a spinner that never resolves.
     */
    @Test
    fun `a list shorter than a page reports its own end`() = runTest {
        val feed = feeds.insert(feed("https://a.example/feed"))
        seed(feed, count = 4)

        val first = refresh(entries.pagedListItems(null, null, true, null))

        assertThat(first.data).hasSize(4)
        assertThat(first.nextKey).isNull()
    }

    // ---- what one write costs ---------------------------------------------------

    /**
     * Marking one article read used to re-do the work of the whole screen: the flow
     * re-emitted all 2000 rows. Paging turns that into an invalidation, and the reload
     * that follows is one page at the reader's anchor.
     *
     * Asserted against the `PagingSource` rather than through the differ because the
     * claim is about *what gets read from the database*, and the differ would only show
     * what survived afterwards.
     */
    @Test
    fun `flipping one entry's read flag reloads a page, not every row`() = runBlocking {
        val feed = feeds.insert(feed("https://a.example/feed"))
        val ids = seed(feed, count = 2_000)
        val source = entries.pagedListItems(null, null, true, null)
        assertThat(refresh(source).data).hasSize(page)

        entries.setRead(listOf(ids.first()), isRead = true, readAt = NOW)

        // Room notices the write on its own executor, so the wait is in wall-clock time —
        // NOTES.md's standing rule for anything that comes back through the database.
        awaitInvalidation(source)
        // Room retires the invalidated source; Paging builds a fresh one and re-reads
        // around where the reader was standing, not from the top and not all of it.
        assertThat(source.invalid).isTrue()
        val reloaded = entries.pagedListItems(null, null, true, null)
            .load(
                PagingSource.LoadParams.Refresh(
                    key = 600,
                    loadSize = PerchPaging.config.initialLoadSize,
                    placeholdersEnabled = false,
                ),
            ) as PagingSource.LoadResult.Page
        assertThat(reloaded.data).hasSize(page)
        assertThat(reloaded.data.first().title).isEqualTo("Entry 600")
    }

    /** Polls the tracker until the write reaches it; the assertion after it says so if not. */
    private fun awaitInvalidation(source: PagingSource<*, *>) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline && !source.invalid) {
            db.invalidationTracker.refreshVersionsSync()
            Thread.sleep(20L)
        }
    }

    // ---- one stream across a page boundary ---------------------------------------

    /**
     * W03's contract, asked of the paged list rather than the query: the Feed is one
     * chronological stream, and a page boundary is not a seam in it. Two folders are
     * seeded half a step out of phase, so a list that still grouped by folder would show
     * 45 of one and then 45 of the other and fail the alternation below.
     */
    @Test
    fun `entries from two folders interleave newest first across a page boundary`() = runTest {
        val alpha = folders.insert(folder("Alpha", sortIndex = 0))
        val beta = folders.insert(folder("Beta", sortIndex = 1))
        seed(feeds.insert(feed("https://a.example/feed", alpha)), count = 45)
        seed(feeds.insert(feed("https://b.example/feed", beta)), count = 45, guidPrefix = "b", shift = 500L)

        val paged = repo.pagedEntries(includeRead = true).asSnapshot { scrollTo(3 * page) }

        assertThat(paged).hasSize(90)
        assertThat(paged.map { it.publishedAt })
            .isInOrder(Comparator<Long> { a, b -> b.compareTo(a) })
        // Alternating folder names is what "mixed together" means; and the pair straddling
        // the first page boundary alternates too, so paging did not re-group anything.
        assertThat(paged.map { it.folderName }.distinct()).containsExactly("Alpha", "Beta")
        assertThat(paged[page - 1].folderName).isNotEqualTo(paged[page].folderName)
    }

    // ---- fixtures ---------------------------------------------------------------

    private suspend fun refresh(
        source: PagingSource<Int, EntryListItem>,
    ): PagingSource.LoadResult.Page<Int, EntryListItem> =
        source.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = PerchPaging.config.initialLoadSize,
                placeholdersEnabled = false,
            ),
        ) as PagingSource.LoadResult.Page

    /** Newest first by publication, so entry *n* is the *n*th row of the list. */
    private suspend fun seed(
        feedId: Long,
        count: Int,
        guidPrefix: String = "a",
        shift: Long = 0L,
    ): List<Long> =
        (0 until count).map { index ->
            entries.insert(
                EntryEntity(
                    feedId = feedId,
                    guid = "$guidPrefix$index",
                    title = "Entry $index",
                    link = "https://example.com/$guidPrefix$index",
                    author = null,
                    publishedAt = NOW - index * 1_000L - shift,
                    publishedIsEstimated = false,
                    summary = null,
                    contentHtml = null,
                    imageUrl = null,
                    readAt = null,
                    fetchedAt = NOW,
                ),
            )
        }

    private fun feed(url: String, folderId: Long = FolderEntity.UNCATEGORIZED_ID) = FeedEntity(
        feedUrl = url,
        siteUrl = url,
        title = "Source $url",
        customTitle = null,
        faviconUrl = null,
        etag = null,
        lastModified = null,
        lastFetchedAt = null,
        lastSuccessAt = null,
        lastError = null,
        addedAt = 0L,
        folderId = folderId,
    )

    private fun folder(name: String, sortIndex: Int) =
        FolderEntity(name = name, sortIndex = sortIndex, createdAt = 0L)

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
