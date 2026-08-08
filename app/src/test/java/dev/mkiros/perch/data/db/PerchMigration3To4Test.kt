package dev.mkiros.perch.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Migration 3 → 4 (U10), run against a **real, populated version-3 file**.
 *
 * U10 adds two facts an entry could not previously carry: whether the body the feed gave
 * it was only ever a `<description>` excerpt, and when — if ever — Perch went and fetched
 * the article itself. Both are additive columns with defaults, so the migration's whole
 * job is to leave the phone's existing articles and reader state exactly where they were.
 */
@RunWith(RobolectricTestRunner::class)
class PerchMigration3To4Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file: File = context.getDatabasePath("migration-3-to-4.db")
    private var db: PerchDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        SQLiteDatabase.deleteDatabase(file)
    }

    @Test
    fun `upgrading a populated v3 database keeps every feed, entry and body`() = runTest {
        seedVersion3()

        val migrated = openAtCurrentVersion()

        assertThat(migrated.feedDao().getAll().map { it.feedUrl }).containsExactly(
            "https://fabiensanglard.net/rss.xml",
            "https://gpuopen.com/feed.xml",
        )
        assertThat(migrated.entryDao().countAll()).isEqualTo(3)
        assertThat(migrated.entryDao().findByGuid(feedId = 2, guid = "guid-c")?.contentHtml)
            .isEqualTo("<p>A 194 character teaser.</p>")
    }

    @Test
    fun `upgrading leaves read, saved and liked state untouched`() = runTest {
        seedVersion3()

        val migrated = openAtCurrentVersion()
        val saved = migrated.entryDao().findByGuid(feedId = 1, guid = "guid-b")

        assertThat(saved?.isSaved).isTrue()
        assertThat(saved?.savedAt).isEqualTo(7_007L)
        assertThat(saved?.isStarred).isTrue()
        assertThat(saved?.starredAt).isEqualTo(8_008L)
        assertThat(migrated.entryDao().findByGuid(feedId = 1, guid = "guid-a")?.readAt)
            .isEqualTo(600L)
    }

    /**
     * Every pre-U10 entry arrives knowing neither thing about itself. "Not an excerpt" and
     * "never extracted" are the right defaults: the first is what the parser will say the
     * next time the feed is fetched, and the second is simply true.
     */
    @Test
    fun `existing entries arrive as neither excerpts nor extractions`() = runTest {
        seedVersion3()

        val migrated = openAtCurrentVersion()

        val all = listOf("guid-a", "guid-b").map { migrated.entryDao().findByGuid(1, it)!! } +
            migrated.entryDao().findByGuid(2, "guid-c")!!
        assertThat(all.none { it.bodyIsExcerpt }).isTrue()
        assertThat(all.all { it.fullTextAt == null }).isTrue()
    }

    private fun seedVersion3() {
        file.parentFile?.mkdirs()
        SQLiteDatabase.deleteDatabase(file)
        val v3 = SQLiteDatabase.openOrCreateDatabase(file, null)
        ExportedSchemas.createStatements(version = 3).forEach(v3::execSQL)
        v3.execSQL(
            """
            INSERT INTO folders (id, name, sortIndex, createdAt)
            VALUES (1, 'Uncategorized', 0, 900), (2, 'Graphics', 1, 950)
            """.trimIndent(),
        )
        v3.execSQL(
            """
            INSERT INTO feeds (id, feedUrl, siteUrl, title, customTitle, faviconUrl, etag,
                lastModified, lastFetchedAt, lastSuccessAt, lastError, consecutiveFailures,
                addedAt, sortIndex, folderId)
            VALUES
                (1, 'https://fabiensanglard.net/rss.xml', 'https://fabiensanglard.net',
                 'Fabien Sanglard', NULL, NULL, NULL, NULL, 1000, 1000, NULL, 0, 900, 0, 2),
                (2, 'https://gpuopen.com/feed.xml', 'https://gpuopen.com',
                 'AMD GPUOpen', NULL, NULL, NULL, NULL, 1000, 1000, NULL, 0, 950, 1, 2)
            """.trimIndent(),
        )
        v3.execSQL(
            """
            INSERT INTO entries (feedId, guid, title, link, author, publishedAt,
                publishedIsEstimated, summary, contentHtml, imageUrl, isRead, readAt,
                isSaved, savedAt, isStarred, starredAt, fetchedAt)
            VALUES
                (1, 'guid-a', 'A dock that wakes up', 'https://fabiensanglard.net/tb4/', NULL,
                 500, 0, NULL, NULL, NULL, 1, 600, 0, NULL, 0, NULL, 1000),
                (1, 'guid-b', 'Silpheed', 'https://fabiensanglard.net/silpheed/', NULL,
                 400, 0, NULL, NULL, NULL, 0, NULL, 1, 7007, 1, 8008, 1000),
                (2, 'guid-c', 'Adaptive subdivision', 'https://gpuopen.com/learn/one/', NULL,
                 300, 0, 'A 194 character teaser.', '<p>A 194 character teaser.</p>', NULL,
                 0, NULL, 0, NULL, 0, NULL, 1000)
            """.trimIndent(),
        )
        v3.version = 3
        v3.close()
    }

    private fun openAtCurrentVersion(): PerchDatabase =
        Room.databaseBuilder(context, PerchDatabase::class.java, file.name)
            .addMigrations(*PerchDatabase.MIGRATIONS)
            .build()
            .also { db = it }
}
