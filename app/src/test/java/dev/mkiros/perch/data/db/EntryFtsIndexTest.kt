package dev.mkiros.perch.data.db

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.db.entity.FolderEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The search index on a **fresh install** (S08, #28) — the other half of
 * `PerchMigration6To7Test`, which covers an upgrade.
 *
 * Every article in the app arrives through [EntryDao.upsertAll], so that is where rows go
 * in; every article leaves through `entries`' `ON DELETE CASCADE`, which is SQL and never
 * calls Kotlin, so that is why they come out through a trigger. The two halves are asserted
 * separately because they fail separately: an index that never gains rows finds nothing,
 * and an index that never sheds them keeps answering with articles the reader deleted.
 */
@RunWith(RobolectricTestRunner::class)
class EntryFtsIndexTest {

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

    @Test
    fun `a newly written article is findable by a word in its title`() = runTest {
        val feedId = feeds.insert(feed())

        entries.upsertAll(listOf(entry(feedId, "guid-a", title = "Investigations using Strava")))

        assertThat(matching("strava")).containsExactly(idOf(feedId, "guid-a"))
    }

    @Test
    fun `a newly written article is findable by a word in its body`() = runTest {
        val feedId = feeds.insert(feed())

        entries.upsertAll(
            listOf(
                entry(
                    feedId,
                    "guid-a",
                    contentHtml = "<p>The heatmap gave away the perimeter.</p>",
                ),
            ),
        )

        assertThat(matching("heatmap")).containsExactly(idOf(feedId, "guid-a"))
    }

    @Test
    fun `an article with no body is findable by the excerpt the feed shipped`() = runTest {
        val feedId = feeds.insert(feed())

        entries.upsertAll(
            listOf(entry(feedId, "guid-a", contentHtml = null, summary = "Occupancy and stalls.")),
        )

        assertThat(matching("occupancy")).containsExactly(idOf(feedId, "guid-a"))
    }

    @Test
    fun `the index holds plain text, so markup is not searchable`() = runTest {
        val feedId = feeds.insert(feed())

        entries.upsertAll(
            listOf(
                entry(
                    feedId,
                    "guid-a",
                    contentHtml = """<p class="lede">Words.</p><a href="https://x.test">here</a>""",
                ),
            ),
        )

        assertThat(matching("lede")).isEmpty()
        assertThat(matching("https")).isEmpty()
        assertThat(matching("words")).hasSize(1)
    }

    @Test
    fun `re-writing an article replaces its index row rather than adding a second`() = runTest {
        val feedId = feeds.insert(feed())
        entries.upsertAll(listOf(entry(feedId, "guid-a", contentHtml = "<p>Draft prose.</p>")))

        entries.upsertAll(listOf(entry(feedId, "guid-a", contentHtml = "<p>Revised prose.</p>")))

        assertThat(matching("draft")).isEmpty()
        assertThat(matching("revised")).containsExactly(idOf(feedId, "guid-a"))
        assertThat(indexSize()).isEqualTo(1)
    }

    @Test
    fun `a recovered full article is indexed, not just the stub it replaced`() = runTest {
        val feedId = feeds.insert(feed())
        entries.upsertAll(listOf(entry(feedId, "guid-a", contentHtml = "<p>Continue reading.</p>")))
        val stored = entries.findByGuid(feedId, "guid-a")!!

        // What ArticleTextRepository.loadFullText does once it has the real page.
        val recovered = stored.copy(contentHtml = "<p>The whole argument, at last.</p>")
        entries.update(recovered)
        entries.index(recovered)

        assertThat(matching("argument")).containsExactly(stored.id)
    }

    @Test
    fun `deleting a source sheds its articles from the index`() = runTest {
        val kept = feeds.insert(feed("https://gpuopen.com/feed.xml"))
        val removed = feeds.insert(feed("https://fabiensanglard.net/rss.xml"))
        entries.upsertAll(
            listOf(
                entry(kept, "guid-a", title = "Compute shaders"),
                entry(removed, "guid-b", title = "Silpheed"),
            ),
        )

        feeds.deleteByIds(listOf(removed))

        assertThat(matching("silpheed")).isEmpty()
        assertThat(matching("shaders")).hasSize(1)
        assertThat(indexSize()).isEqualTo(1)
    }

    private suspend fun idOf(feedId: Long, guid: String): Long =
        entries.findByGuid(feedId, guid)!!.id

    private fun matching(term: String): List<Long> = db.openHelper.readableDatabase
        .query("SELECT rowid FROM entries_fts WHERE entries_fts MATCH ?", arrayOf(term))
        .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }

    private fun indexSize(): Int = db.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM entries_fts")
        .use { it.moveToFirst(); it.getInt(0) }

    private fun feed(feedUrl: String = "https://gijn.org/feed/") = FeedEntity(
        feedUrl = feedUrl,
        siteUrl = null,
        title = "Example",
        customTitle = null,
        faviconUrl = null,
        etag = null,
        lastModified = null,
        lastFetchedAt = null,
        lastSuccessAt = null,
        lastError = null,
        addedAt = 0,
        folderId = FolderEntity.UNCATEGORIZED_ID,
    )

    private fun entry(
        feedId: Long,
        guid: String,
        title: String = "Untitled",
        contentHtml: String? = null,
        summary: String? = null,
    ) = EntryEntity(
        feedId = feedId,
        guid = guid,
        title = title,
        link = null,
        author = null,
        publishedAt = 0,
        publishedIsEstimated = false,
        summary = summary,
        contentHtml = contentHtml,
        imageUrl = null,
        readAt = null,
        fetchedAt = 0,
    )
}
