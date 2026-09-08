package dev.mkiros.perch.data.repo

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
 * Asking the index a reader's question (S09, #28).
 *
 * S08 built the index and asserted that it holds the right rows; this asserts what comes
 * back out of it, as the reader's list rather than as rowids. The rules that are easy to
 * get wrong, and so are each pinned here:
 *
 * - **Search ignores the time window and the read filter.** You are looking for something
 *   you may have read months ago, so a Feed showing "Past 24 Hours" of unread articles must
 *   not narrow the search that was opened from it (PLAN-9 §0.8).
 * - **Search does not filter `isSynthetic`.** A pasted link is a stored article; #31 kept it
 *   out of the *stream*, not out of the reader's memory (PLAN-9 §0.3).
 * - **The scope is inherited, not re-chosen.** Search opened from a source, a folder,
 *   To-Read or Liked stays inside it until the reader widens it.
 * - **The paging twin answers exactly what the flow answers.** Two implementations of one
 *   query is the standing hazard here (`EntryQueries`' KDoc); only one of them is ever on
 *   screen, so a drift would be invisible.
 */
@RunWith(RobolectricTestRunner::class)
class EntrySearchTest {

    private lateinit var db: PerchDatabase
    private lateinit var feeds: FeedDao
    private lateinit var folders: FolderDao
    private lateinit var entries: EntryDao
    private lateinit var repo: EntryRepository

    @Before
    fun openDatabase() {
        // `asSnapshot` drives the differ on the main dispatcher (U07a).
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

    // ---- what a hit is ----------------------------------------------------------

    @Test
    fun `a word in the title finds the article`() = runTest {
        val feedId = feeds.insert(feed("https://gijn.org/feed/"))
        entries.upsertAll(
            listOf(
                entry(feedId, "a", title = "Investigations Using the Strava Fitness App"),
                entry(feedId, "b", title = "Something else entirely"),
            ),
        )

        assertThat(titles(repo.searchEntries("strava"))).containsExactly(
            "Investigations Using the Strava Fitness App",
        )
    }

    @Test
    fun `a word in the recovered article body finds it, though no title carries it`() = runTest {
        val feedId = feeds.insert(feed("https://gijn.org/feed/"))
        entries.upsertAll(
            listOf(
                entry(
                    feedId,
                    "a",
                    title = "Investigations",
                    contentHtml = "<p>The heatmap gave away the perimeter.</p>",
                ),
                entry(feedId, "b", title = "Something else", contentHtml = "<p>Nothing.</p>"),
            ),
        )

        assertThat(titles(repo.searchEntries("heatmap"))).containsExactly("Investigations")
    }

    @Test
    fun `a never-opened article is findable by the excerpt the feed shipped`() = runTest {
        val feedId = feeds.insert(feed("https://gijn.org/feed/"))
        entries.upsertAll(
            listOf(
                entry(
                    feedId,
                    "a",
                    title = "Investigations",
                    contentHtml = null,
                    summary = "Occupancy of a military base, mapped from joggers.",
                ),
            ),
        )

        assertThat(titles(repo.searchEntries("joggers"))).containsExactly("Investigations")
    }

    @Test
    fun `every word has to appear, and the last one only has to have started`() = runTest {
        val feedId = feeds.insert(feed("https://gijn.org/feed/"))
        entries.upsertAll(
            listOf(
                entry(feedId, "a", title = "Strava heatmaps and open source intelligence"),
                entry(feedId, "b", title = "Strava alone"),
                entry(feedId, "c", title = "Heatmaps alone"),
            ),
        )

        assertThat(titles(repo.searchEntries("strava heat"))).containsExactly(
            "Strava heatmaps and open source intelligence",
        )
    }

    @Test
    fun `results are newest first, like every other list`() = runTest {
        val feedId = feeds.insert(feed("https://gijn.org/feed/"))
        entries.upsertAll(
            listOf(
                entry(feedId, "a", title = "Strava one", publishedAt = 1_000L),
                entry(feedId, "b", title = "Strava two", publishedAt = 3_000L),
                entry(feedId, "c", title = "Strava three", publishedAt = 2_000L),
            ),
        )

        assertThat(titles(repo.searchEntries("strava")))
            .containsExactly("Strava two", "Strava three", "Strava one")
            .inOrder()
    }

    // ---- what search deliberately does not filter --------------------------------

    @Test
    fun `an article read months ago is still findable`() = runTest {
        val feedId = feeds.insert(feed("https://gijn.org/feed/"))
        entries.upsertAll(
            listOf(entry(feedId, "a", title = "Strava investigations", publishedAt = 1L)),
        )
        val stored = entries.findByGuid(feedId, "a")!!
        entries.setRead(listOf(stored.id), isRead = true, readAt = NOW)

        val hits = repo.searchEntries("strava").first()

        assertThat(hits.map { it.title }).containsExactly("Strava investigations")
        assertThat(hits.single().isRead).isTrue()
    }

    @Test
    fun `a pasted link is a stored article and is findable`() = runTest {
        // The synthetic source is seeded by the database itself (PLAN-6 §0.3), not by a test.
        val pasted = feeds.findByUrl(FeedEntity.SAVED_LINKS_FEED_URL)!!.id
        val subscribed = feeds.insert(feed("https://gijn.org/feed/"))
        entries.upsertAll(
            listOf(
                entry(pasted, "https://gijn.org/stories/strava/", title = "Strava, pasted"),
                entry(subscribed, "b", title = "Strava, subscribed"),
            ),
        )

        assertThat(titles(repo.searchEntries("strava")))
            .containsExactly("Strava, pasted", "Strava, subscribed")
        // The Feed still does not carry it — #31 is about the stream, not about memory.
        assertThat(repo.observeEntries(includeRead = true).first().map { it.title })
            .containsExactly("Strava, subscribed")
    }

    // ---- the scope the search was opened from ------------------------------------

    @Test
    fun `a search opened from a source stays inside that source`() = runTest {
        val gijn = feeds.insert(feed("https://gijn.org/feed/"))
        val other = feeds.insert(feed("https://bellingcat.com/feed/"))
        entries.upsertAll(
            listOf(
                entry(gijn, "a", title = "Strava here"),
                entry(other, "b", title = "Strava there"),
            ),
        )

        assertThat(titles(repo.searchEntries("strava", feedId = gijn)))
            .containsExactly("Strava here")
    }

    @Test
    fun `a search opened from a folder stays inside that folder`() = runTest {
        val osint = folders.insert(folder("OSINT"))
        val inFolder = feeds.insert(feed("https://gijn.org/feed/", folderId = osint))
        val outside = feeds.insert(feed("https://bellingcat.com/feed/"))
        entries.upsertAll(
            listOf(
                entry(inFolder, "a", title = "Strava here"),
                entry(outside, "b", title = "Strava there"),
            ),
        )

        assertThat(titles(repo.searchEntries("strava", folderId = osint)))
            .containsExactly("Strava here")
    }

    @Test
    fun `a search opened from To-Read stays inside the queue`() = runTest {
        val feedId = feeds.insert(feed("https://gijn.org/feed/"))
        entries.upsertAll(
            listOf(
                entry(feedId, "a", title = "Strava saved"),
                entry(feedId, "b", title = "Strava unsaved"),
            ),
        )
        repo.setSaved(entries.findByGuid(feedId, "a")!!.id, isSaved = true)

        assertThat(titles(repo.searchEntries("strava", savedOnly = true)))
            .containsExactly("Strava saved")
    }

    @Test
    fun `a search opened from Liked stays inside the likes`() = runTest {
        val feedId = feeds.insert(feed("https://gijn.org/feed/"))
        entries.upsertAll(
            listOf(
                entry(feedId, "a", title = "Strava liked"),
                entry(feedId, "b", title = "Strava unliked"),
            ),
        )
        repo.setLiked(entries.findByGuid(feedId, "a")!!.id, isLiked = true)

        assertThat(titles(repo.searchEntries("strava", likedOnly = true)))
            .containsExactly("Strava liked")
    }

    @Test
    fun `widening a narrowed search is dropping the scope, and finds the rest`() = runTest {
        val gijn = feeds.insert(feed("https://gijn.org/feed/"))
        val other = feeds.insert(feed("https://bellingcat.com/feed/"))
        entries.upsertAll(
            listOf(
                entry(gijn, "a", title = "Strava here"),
                entry(other, "b", title = "Strava there"),
            ),
        )

        assertThat(titles(repo.searchEntries("strava"))).hasSize(2)
    }

    // ---- what a reader can type --------------------------------------------------

    @Test
    fun `an empty question answers with nothing rather than with everything`() = runTest {
        val feedId = feeds.insert(feed("https://gijn.org/feed/"))
        entries.upsertAll(listOf(entry(feedId, "a", title = "Strava investigations")))

        assertThat(repo.searchEntries("").first()).isEmpty()
        assertThat(repo.searchEntries("   ").first()).isEmpty()
        assertThat(repo.pagedSearch("").asSnapshot()).isEmpty()
    }

    /**
     * The reason [dev.mkiros.perch.data.db.FtsQuery] exists, asserted where it matters: the
     * reader's raw text never reaches `MATCH`. Every one of these throws
     * `SQLiteException: fts: syntax error` if the repository passes it straight through.
     */
    @Test
    fun `punctuation a reader types does not reach the query parser`() = runTest {
        val feedId = feeds.insert(feed("https://gijn.org/feed/"))
        entries.upsertAll(listOf(entry(feedId, "a", title = "Strava investigations")))

        assertThat(titles(repo.searchEntries("\"strava"))).containsExactly("Strava investigations")
        assertThat(titles(repo.searchEntries("strava*"))).containsExactly("Strava investigations")
        assertThat(titles(repo.searchEntries("strava AND"))).isEmpty()
        assertThat(repo.searchEntries("🚴").first()).isEmpty()
    }

    // ---- the twin ----------------------------------------------------------------

    @Test
    fun `the paged search answers exactly what the flow answers`() = runTest {
        val gijn = feeds.insert(feed("https://gijn.org/feed/"))
        val other = feeds.insert(feed("https://bellingcat.com/feed/"))
        repeat(40) { i ->
            entries.upsertAll(
                listOf(
                    entry(gijn, "a$i", title = "Strava story $i", publishedAt = i.toLong()),
                    entry(other, "b$i", title = "Elsewhere $i", publishedAt = i.toLong()),
                ),
            )
        }

        val flow = repo.searchEntries("strava", feedId = gijn).first().map { it.title }
        val firstPage = repo.pagedSearch("strava", feedId = gijn).asSnapshot().map { it.title }
        val scrolled = repo.pagedSearch("strava", feedId = gijn)
            .asSnapshot { scrollTo(PerchPaging.PAGE_SIZE) }.map { it.title }

        assertThat(flow).hasSize(40)
        // A page at a time, in the flow's order, and scrolling reaches the same 40 —
        // nothing from the other source leaks in at a page boundary.
        assertThat(firstPage).isEqualTo(flow.take(PerchPaging.PAGE_SIZE))
        assertThat(scrolled).isEqualTo(flow)
    }

    private suspend fun titles(flow: Flow<List<EntryListItem>>) = flow.first().map { it.title }

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

    private fun folder(name: String) = FolderEntity(name = name, sortIndex = 0, createdAt = 0L)

    private fun entry(
        feedId: Long,
        guid: String,
        title: String = "Untitled",
        contentHtml: String? = null,
        summary: String? = null,
        publishedAt: Long = 0L,
    ) = EntryEntity(
        feedId = feedId,
        guid = guid,
        title = title,
        link = null,
        author = null,
        publishedAt = publishedAt,
        publishedIsEstimated = false,
        summary = summary,
        contentHtml = contentHtml,
        imageUrl = null,
        readAt = null,
        fetchedAt = 0L,
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
