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
 * Migration 9 → 10 (F07/#68), run against a **real, populated version-9 file**.
 *
 * One new table and no statement against an existing one: `archive_posts`, the remembered
 * archive plan (PLAN-12 §0.4). An upgrade has nothing to park — the table arrives empty and
 * the next `plan()` fills it — so every feed and entry comes through untouched. The one
 * behaviour the shape must prove is the foreign key: removing a source takes its remembered
 * archive with it, the way `entries` already cascades.
 */
@RunWith(RobolectricTestRunner::class)
class PerchMigration9To10Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file: File = context.getDatabasePath("migration-9-to-10.db")
    private var db: PerchDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        SQLiteDatabase.deleteDatabase(file)
    }

    @Test
    fun `every feed and entry survives, and the archive table arrives empty`() = runTest {
        seedVersion9()

        val migrated = openAtCurrentVersion()

        assertThat(migrated.feedDao().findById(FEED_ID)?.title).isEqualTo("bellingcat")
        assertThat(migrated.entryDao().countAll()).isEqualTo(2)
        assertThat(migrated.entryDao().findByGuid(FEED_ID, "guid-a")?.title).isEqualTo("The ship")
        assertThat(count(migrated, "SELECT COUNT(*) FROM archive_posts")).isEqualTo(0)
    }

    @Test
    fun `deleting a feed takes its remembered archive with it`() = runTest {
        seedVersion9()
        val migrated = openAtCurrentVersion()
        migrated.openHelper.writableDatabase.execSQL(
            "INSERT INTO archive_posts (feedId, url, lastmod, discoveredAt, fetchedAt) " +
                "VALUES ($FEED_ID, 'https://www.bellingcat.com/news/2020/01/01/old/', NULL, 2000, NULL)",
        )
        assertThat(count(migrated, "SELECT COUNT(*) FROM archive_posts")).isEqualTo(1)

        migrated.feedDao().deleteByIds(listOf(FEED_ID))

        assertThat(count(migrated, "SELECT COUNT(*) FROM archive_posts")).isEqualTo(0)
    }

    private fun count(database: PerchDatabase, sql: String): Int =
        database.openHelper.readableDatabase.query(sql).use { it.moveToFirst(); it.getInt(0) }

    /** A version-9 file as a phone running v0.7.x-with-F02 has it: one source, two articles. */
    private fun seedVersion9() =
        ExportedSchemas.seedVersion(file, version = 9) { v9 ->
            v9.execSQL(
                "CREATE TRIGGER IF NOT EXISTS `entries_fts_delete` AFTER DELETE ON `entries` " +
                    "BEGIN DELETE FROM `entries_fts` WHERE `rowid` = OLD.`id`; END",
            )
            v9.execSQL("INSERT INTO folders (id, name, sortIndex, createdAt) VALUES (1, 'Uncategorized', 0, 900)")
            v9.execSQL(
                """
                INSERT INTO feeds (id, feedUrl, siteUrl, title, customTitle, faviconUrl, etag,
                    lastModified, lastFetchedAt, lastSuccessAt, lastError, consecutiveFailures,
                    addedAt, sortIndex, folderId, isSynthetic)
                VALUES ($FEED_ID, 'https://www.bellingcat.com/feed/', 'https://www.bellingcat.com',
                     'bellingcat', NULL, NULL, NULL, NULL, 1000, 1000, NULL, 0, 900, 0, 1, 0)
                """.trimIndent(),
            )
            v9.execSQL(
                """
                INSERT INTO entries (id, feedId, guid, title, link, author, publishedAt,
                    publishedIsEstimated, summary, contentHtml, imageUrl, isRead, readAt,
                    isSaved, savedAt, isStarred, starredAt, bodyIsExcerpt, fullTextAt, fetchedAt,
                    scrollPosition)
                VALUES
                    (1, $FEED_ID, 'guid-a', 'The ship', 'https://www.bellingcat.com/news/2026/09/01/the-ship/', NULL,
                     500, 0, NULL, '<p>The feed gave us this.</p>', NULL, 0, NULL, 0, NULL, 0, NULL, 0, NULL, 1000, 0),
                    (2, $FEED_ID, 'guid-b', 'The plane', 'https://www.bellingcat.com/news/2026/09/02/the-plane/', NULL,
                     400, 0, NULL, '<p>So did this.</p>', NULL, 1, 600, 0, NULL, 0, NULL, 0, NULL, 1000, 0)
                """.trimIndent(),
            )
            v9.execSQL("INSERT INTO entries_fts (rowid, title, body) SELECT id, title, COALESCE(contentHtml, summary, '') FROM entries")
        }

    private fun openAtCurrentVersion(): PerchDatabase =
        ExportedSchemas.openAtCurrentVersion(context, file).also { db = it }

    private companion object {
        const val FEED_ID = 1L
    }
}
