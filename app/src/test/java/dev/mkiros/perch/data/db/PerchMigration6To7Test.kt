package dev.mkiros.perch.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Migration 6 → 7 (S08/#28), run against a **real, populated version-6 file**.
 *
 * The search index is a standalone FTS4 table Perch writes itself (PLAN-9 §0.8), so an
 * upgrade has three jobs beyond adding the table: backfill every article already on the
 * phone — an index that starts empty is a search feature that finds nothing until the next
 * refresh — install the delete trigger, and store **plain text**, because a body indexed as
 * markup makes a search for `class` or `https` return the whole database.
 *
 * The usual pair of assertions apply on top of that: every existing feed and entry survives
 * untouched.
 */
@RunWith(RobolectricTestRunner::class)
class PerchMigration6To7Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file: File = context.getDatabasePath("migration-6-to-7.db")
    private var db: PerchDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        SQLiteDatabase.deleteDatabase(file)
    }

    @Test
    fun `upgrading a populated v6 database keeps every feed and entry`() = runTest {
        seedVersion6()

        val migrated = openAtCurrentVersion()

        assertThat(migrated.feedDao().getAll().map { it.feedUrl }).containsExactly(
            "https://fabiensanglard.net/rss.xml",
            "https://gpuopen.com/feed.xml",
        )
        assertThat(migrated.entryDao().countAll()).isEqualTo(3)
    }

    @Test
    fun `upgrading leaves read and saved state untouched`() = runTest {
        seedVersion6()

        val migrated = openAtCurrentVersion()
        val feedId = migrated.feedDao().findByUrl("https://fabiensanglard.net/rss.xml")!!.id
        val saved = migrated.entryDao().findByGuid(feedId, "guid-b")

        assertThat(saved?.isSaved).isTrue()
        assertThat(saved?.savedAt).isEqualTo(7_007L)
    }

    @Test
    fun `the backfill indexes every article already on the phone, by title`() = runTest {
        seedVersion6()

        val migrated = openAtCurrentVersion()

        assertThat(matchingIds(migrated, "silpheed")).hasSize(1)
        assertThat(matchingIds(migrated, "dock")).hasSize(1)
        assertThat(matchingIds(migrated, "shaders")).hasSize(1)
    }

    @Test
    fun `the backfill indexes the article body, not only its title`() = runTest {
        seedVersion6()

        val migrated = openAtCurrentVersion()
        val feedId = migrated.feedDao().findByUrl("https://fabiensanglard.net/rss.xml")!!.id
        val expected = migrated.entryDao().findByGuid(feedId, "guid-b")!!.id

        assertThat(matchingIds(migrated, "thunderbolt")).containsExactly(expected)
    }

    @Test
    fun `an article whose body is only the feed's excerpt is still findable`() = runTest {
        seedVersion6()

        val migrated = openAtCurrentVersion()
        val feedId = migrated.feedDao().findByUrl("https://gpuopen.com/feed.xml")!!.id
        val expected = migrated.entryDao().findByGuid(feedId, "guid-c")!!.id

        assertThat(matchingIds(migrated, "wavefront")).containsExactly(expected)
    }

    @Test
    fun `the index holds plain text, so markup is not searchable`() = runTest {
        seedVersion6()

        val migrated = openAtCurrentVersion()

        // Every seeded body is wrapped in these; indexing raw HTML would return all of them.
        assertThat(matchingIds(migrated, "blockquote")).isEmpty()
        assertThat(matchingIds(migrated, "highlight")).isEmpty()
        assertThat(matchingIds(migrated, "https")).isEmpty()
    }

    @Test
    fun `deleting a feed sheds its rows from the index`() = runTest {
        seedVersion6()

        val migrated = openAtCurrentVersion()
        val feed = migrated.feedDao().findByUrl("https://fabiensanglard.net/rss.xml")!!

        migrated.feedDao().deleteByIds(listOf(feed.id))

        // The cascade reaches `entries` in SQL and never passes through Kotlin, so only a
        // trigger can keep the index honest.
        assertThat(matchingIds(migrated, "silpheed")).isEmpty()
        assertThat(matchingIds(migrated, "dock")).isEmpty()
        assertThat(matchingIds(migrated, "shaders")).hasSize(1)
        assertThat(indexSize(migrated)).isEqualTo(1)
    }

    private fun matchingIds(database: PerchDatabase, term: String): List<Long> =
        database.openHelper.readableDatabase
            .query("SELECT rowid FROM entries_fts WHERE entries_fts MATCH ?", arrayOf(term))
            .use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
            }

    private fun indexSize(database: PerchDatabase): Int =
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM entries_fts")
            .use { it.moveToFirst(); it.getInt(0) }

    private fun seedVersion6() = ExportedSchemas.seedVersion(file, version = 6) { v6 ->
        v6.execSQL(
            """
            INSERT INTO folders (id, name, sortIndex, createdAt)
            VALUES (1, 'Uncategorized', 0, 900)
            """.trimIndent(),
        )
        v6.execSQL(
            """
            INSERT INTO feeds (id, feedUrl, siteUrl, title, customTitle, faviconUrl, etag,
                lastModified, lastFetchedAt, lastSuccessAt, lastError, consecutiveFailures,
                addedAt, sortIndex, folderId, isSynthetic)
            VALUES
                (1, 'https://fabiensanglard.net/rss.xml', 'https://fabiensanglard.net',
                 'Fabien Sanglard', NULL, NULL, NULL, NULL, 1000, 1000, NULL, 0, 900, 0, 1, 0),
                (2, 'https://gpuopen.com/feed.xml', 'https://gpuopen.com',
                 'AMD GPUOpen', NULL, NULL, NULL, NULL, 1000, 1000, NULL, 0, 950, 1, 1, 0)
            """.trimIndent(),
        )
        v6.execSQL(
            """
            INSERT INTO entries (feedId, guid, title, link, author, publishedAt,
                publishedIsEstimated, summary, contentHtml, imageUrl, isRead, readAt,
                isSaved, savedAt, isStarred, starredAt, bodyIsExcerpt, fullTextAt, fetchedAt)
            VALUES
                (1, 'guid-a', 'A dock that wakes up', 'https://fabiensanglard.net/tb4/', NULL,
                 500, 0, NULL,
                 '<blockquote class="highlight"><p>The monitor stays asleep.</p></blockquote>',
                 NULL, 1, 600, 0, NULL, 0, NULL, 0, NULL, 1000),
                (1, 'guid-b', 'Silpheed', 'https://fabiensanglard.net/silpheed/', NULL,
                 400, 0, NULL,
                 '<blockquote class="highlight"><p>A thunderbolt of a shoot em up.</p>' ||
                 '<a href="https://example.org/x">source</a></blockquote>',
                 NULL, 0, NULL, 1, 7007, 1, 8008, 0, NULL, 1000),
                (2, 'guid-c', 'Compute shaders', 'https://gpuopen.com/compute/', NULL,
                 300, 0, 'Occupancy and the wavefront.', NULL,
                 NULL, 0, NULL, 0, NULL, 0, NULL, 1, NULL, 1000)
            """.trimIndent(),
        )
    }

    private fun openAtCurrentVersion(): PerchDatabase =
        ExportedSchemas.openAtCurrentVersion(context, file).also { db = it }
}
