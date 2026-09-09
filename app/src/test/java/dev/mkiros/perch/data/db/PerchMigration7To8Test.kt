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
 * Migration 7 → 8 (E01/#65), run against a **real, populated version-7 file**.
 *
 * One additive column, `entries.scrollPosition`, the body offset an article reopens at.
 * The upgrade has nothing to backfill — every article a reader already has starts at the
 * top, which is exactly where it opened before — so the two assertions that matter are the
 * usual pair: every feed and entry survives, and the reader-owned flags are untouched.
 * Opening through [ExportedSchemas.openAtCurrentVersion] is what makes this a test of the
 * `8.json` export too: Room validates the migrated table against it on open (NOTES.md, S08).
 */
@RunWith(RobolectricTestRunner::class)
class PerchMigration7To8Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file: File = context.getDatabasePath("migration-7-to-8.db")
    private var db: PerchDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        SQLiteDatabase.deleteDatabase(file)
    }

    @Test
    fun `upgrading a populated v7 database keeps every feed and entry`() = runTest {
        seedVersion7()

        val migrated = openAtCurrentVersion()

        assertThat(migrated.feedDao().getAll().map { it.feedUrl }).containsExactly(
            "https://fabiensanglard.net/rss.xml",
            "https://gpuopen.com/feed.xml",
        )
        assertThat(migrated.entryDao().countAll()).isEqualTo(3)
    }

    @Test
    fun `upgrading leaves read, saved and liked state untouched`() = runTest {
        seedVersion7()

        val migrated = openAtCurrentVersion()
        val feedId = migrated.feedDao().findByUrl("https://fabiensanglard.net/rss.xml")!!.id
        val read = migrated.entryDao().findByGuid(feedId, "guid-a")
        val saved = migrated.entryDao().findByGuid(feedId, "guid-b")

        assertThat(read?.isRead).isTrue()
        assertThat(read?.readAt).isEqualTo(600L)
        assertThat(saved?.isSaved).isTrue()
        assertThat(saved?.savedAt).isEqualTo(7_007L)
        assertThat(saved?.isStarred).isTrue()
        assertThat(saved?.starredAt).isEqualTo(8_008L)
    }

    @Test
    fun `every article a reader already has reopens at the top`() = runTest {
        seedVersion7()

        val migrated = openAtCurrentVersion()
        val feedId = migrated.feedDao().findByUrl("https://fabiensanglard.net/rss.xml")!!.id

        assertThat(migrated.entryDao().findByGuid(feedId, "guid-a")?.scrollPosition).isEqualTo(0)
        assertThat(migrated.entryDao().findByGuid(feedId, "guid-b")?.scrollPosition).isEqualTo(0)
    }

    private fun seedVersion7() = ExportedSchemas.seedVersion(file, version = 7) { v7 ->
        v7.execSQL(
            """
            INSERT INTO folders (id, name, sortIndex, createdAt)
            VALUES (1, 'Uncategorized', 0, 900)
            """.trimIndent(),
        )
        v7.execSQL(
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
        v7.execSQL(
            """
            INSERT INTO entries (feedId, guid, title, link, author, publishedAt,
                publishedIsEstimated, summary, contentHtml, imageUrl, isRead, readAt,
                isSaved, savedAt, isStarred, starredAt, bodyIsExcerpt, fullTextAt, fetchedAt)
            VALUES
                (1, 'guid-a', 'A dock that wakes up', 'https://fabiensanglard.net/tb4/', NULL,
                 500, 0, NULL, '<p>The monitor stays asleep.</p>',
                 NULL, 1, 600, 0, NULL, 0, NULL, 0, NULL, 1000),
                (1, 'guid-b', 'Silpheed', 'https://fabiensanglard.net/silpheed/', NULL,
                 400, 0, NULL, '<p>A thunderbolt of a shoot em up.</p>',
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
