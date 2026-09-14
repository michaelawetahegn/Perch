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
 * Migration 8 → 9 (F02/#69), run against a **real, populated version-8 file**.
 *
 * Data only: the shape does not change. Before F01 the archive backfill stored a post the
 * feed had already given us as a second row — the feed's row under its `?p=N` guid, the
 * backfill's copy under the page address as both guid and link. The upgrade merges each
 * such pair into the feed row (the reader's flags OR'd in, the copy's timestamps carried
 * where the feed row had none) and deletes the copy; the `entries_fts_delete` trigger drops
 * the copy's search row without a statement here. Rows that share nothing, and rows with no
 * link at all, are untouched, and a file with no duplicates comes through row for row.
 */
@RunWith(RobolectricTestRunner::class)
class PerchMigration8To9Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file: File = context.getDatabasePath("migration-8-to-9.db")
    private var db: PerchDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        SQLiteDatabase.deleteDatabase(file)
    }

    @Test
    fun `a feed row and its backfill copy become one row that keeps the feed's guid`() = runTest {
        seedVersion8(withDuplicate = true)

        val migrated = openAtCurrentVersion()

        assertThat(rowsLinkingTo(migrated, POST_URL)).isEqualTo(1)
        val keeper = migrated.entryDao().findByGuid(FEED_A, FEED_GUID)
        assertThat(keeper).isNotNull()
        assertThat(migrated.entryDao().findByGuid(FEED_A, POST_URL)).isNull()
        assertThat(migrated.entryDao().countAll()).isEqualTo(4)
    }

    @Test
    fun `the surviving row carries every flag either row had, with its timestamp`() = runTest {
        seedVersion8(withDuplicate = true)

        val migrated = openAtCurrentVersion()
        val keeper = migrated.entryDao().findByGuid(FEED_A, FEED_GUID)!!

        assertThat(keeper.isRead).isTrue()
        assertThat(keeper.readAt).isEqualTo(COPY_READ_AT)
        assertThat(keeper.isSaved).isTrue()
        assertThat(keeper.savedAt).isEqualTo(KEEPER_SAVED_AT)
        assertThat(keeper.isStarred).isFalse()
        assertThat(keeper.starredAt).isNull()
        assertThat(keeper.scrollPosition).isEqualTo(COPY_SCROLL)
    }

    @Test
    fun `the deleted copy leaves no search index row behind`() = runTest {
        seedVersion8(withDuplicate = true)

        val migrated = openAtCurrentVersion()

        assertThat(indexRowsFor(migrated, COPY_ID)).isEqualTo(0)
        assertThat(indexRowsFor(migrated, KEEPER_ID)).isEqualTo(1)
    }

    @Test
    fun `rows that share nothing, and rows with no link, are untouched`() = runTest {
        seedVersion8(withDuplicate = true)

        val migrated = openAtCurrentVersion()

        assertThat(migrated.entryDao().findByGuid(FEED_B, "guid-c")?.link)
            .isEqualTo("https://gpuopen.com/compute/")
        assertThat(migrated.entryDao().findByGuid(FEED_B, "guid-d")?.link)
            .isEqualTo("https://gpuopen.com/occupancy/")
        assertThat(migrated.entryDao().findByGuid(FEED_A, "guid-e")?.link).isNull()
    }

    @Test
    fun `a file with no duplicates comes through row for row`() = runTest {
        seedVersion8(withDuplicate = false)

        val migrated = openAtCurrentVersion()

        assertThat(migrated.entryDao().countAll()).isEqualTo(4)
        val keeper = migrated.entryDao().findByGuid(FEED_A, FEED_GUID)!!
        assertThat(keeper.isRead).isFalse()
        assertThat(keeper.readAt).isNull()
        assertThat(keeper.isSaved).isTrue()
        assertThat(keeper.savedAt).isEqualTo(KEEPER_SAVED_AT)
        assertThat(keeper.scrollPosition).isEqualTo(0)
        assertThat(migrated.entryDao().findByGuid(FEED_B, "guid-c")).isNotNull()
        assertThat(migrated.entryDao().findByGuid(FEED_B, "guid-d")).isNotNull()
        assertThat(migrated.entryDao().findByGuid(FEED_A, "guid-e")).isNotNull()
        assertThat(indexRowsFor(migrated, KEEPER_ID)).isEqualTo(1)
    }

    private fun rowsLinkingTo(database: PerchDatabase, link: String): Int =
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM entries WHERE link = ?", arrayOf(link))
            .use { it.moveToFirst(); it.getInt(0) }

    private fun indexRowsFor(database: PerchDatabase, id: Long): Int =
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM entries_fts WHERE rowid = ?", arrayOf(id))
            .use { it.moveToFirst(); it.getInt(0) }

    /**
     * A version-8 file as a phone running v0.7.0 has it: the exported schema, the
     * `entries_fts_delete` trigger `MIGRATION_6_7` installed, and one search row per entry.
     */
    private fun seedVersion8(withDuplicate: Boolean) =
        ExportedSchemas.seedVersion(file, version = 8) { v8 ->
            v8.execSQL(
                "CREATE TRIGGER IF NOT EXISTS `entries_fts_delete` AFTER DELETE ON `entries` " +
                    "BEGIN DELETE FROM `entries_fts` WHERE `rowid` = OLD.`id`; END",
            )
            v8.execSQL(
                """
                INSERT INTO folders (id, name, sortIndex, createdAt)
                VALUES (1, 'Uncategorized', 0, 900)
                """.trimIndent(),
            )
            v8.execSQL(
                """
                INSERT INTO feeds (id, feedUrl, siteUrl, title, customTitle, faviconUrl, etag,
                    lastModified, lastFetchedAt, lastSuccessAt, lastError, consecutiveFailures,
                    addedAt, sortIndex, folderId, isSynthetic)
                VALUES
                    ($FEED_A, 'https://www.bellingcat.com/feed/', 'https://www.bellingcat.com',
                     'bellingcat', NULL, NULL, NULL, NULL, 1000, 1000, NULL, 0, 900, 0, 1, 0),
                    ($FEED_B, 'https://gpuopen.com/feed.xml', 'https://gpuopen.com',
                     'AMD GPUOpen', NULL, NULL, NULL, NULL, 1000, 1000, NULL, 0, 950, 1, 1, 0)
                """.trimIndent(),
            )
            v8.execSQL(
                """
                INSERT INTO entries (id, feedId, guid, title, link, author, publishedAt,
                    publishedIsEstimated, summary, contentHtml, imageUrl, isRead, readAt,
                    isSaved, savedAt, isStarred, starredAt, bodyIsExcerpt, fullTextAt, fetchedAt,
                    scrollPosition)
                VALUES
                    ($KEEPER_ID, $FEED_A, '$FEED_GUID', 'The ship', '$POST_URL', NULL,
                     500, 0, NULL, '<p>The feed gave us this.</p>',
                     NULL, 0, NULL, 1, $KEEPER_SAVED_AT, 0, NULL, 0, NULL, 1000, 0),
                    (3, $FEED_B, 'guid-c', 'Compute shaders', 'https://gpuopen.com/compute/', NULL,
                     300, 0, 'Occupancy and the wavefront.', NULL,
                     NULL, 0, NULL, 0, NULL, 0, NULL, 1, NULL, 1000, 0),
                    (4, $FEED_B, 'guid-d', 'Occupancy', 'https://gpuopen.com/occupancy/', NULL,
                     200, 0, 'Waves in flight.', NULL,
                     NULL, 0, NULL, 0, NULL, 0, NULL, 1, NULL, 1000, 0),
                    (5, $FEED_A, 'guid-e', 'No link at all', NULL, NULL,
                     100, 0, NULL, '<p>A pasted note.</p>',
                     NULL, 0, NULL, 0, NULL, 0, NULL, 0, NULL, 1000, 0)
                """.trimIndent(),
            )
            if (withDuplicate) {
                v8.execSQL(
                    """
                    INSERT INTO entries (id, feedId, guid, title, link, author, publishedAt,
                        publishedIsEstimated, summary, contentHtml, imageUrl, isRead, readAt,
                        isSaved, savedAt, isStarred, starredAt, bodyIsExcerpt, fullTextAt, fetchedAt,
                        scrollPosition)
                    VALUES
                        ($COPY_ID, $FEED_A, '$POST_URL', 'The ship - bellingcat', '$POST_URL', NULL,
                         500, 0, NULL, '<p>The backfill fetched this.</p>',
                         NULL, 1, $COPY_READ_AT, 0, NULL, 0, NULL, 0, 1200, 1200, $COPY_SCROLL)
                    """.trimIndent(),
                )
            }
            v8.execSQL(
                """
                INSERT INTO entries_fts (rowid, title, body)
                SELECT id, title, COALESCE(contentHtml, summary, '') FROM entries
                """.trimIndent(),
            )
        }

    private fun openAtCurrentVersion(): PerchDatabase =
        ExportedSchemas.openAtCurrentVersion(context, file).also { db = it }

    private companion object {
        const val FEED_A = 1L
        const val FEED_B = 2L
        const val KEEPER_ID = 1L
        const val COPY_ID = 2L
        const val FEED_GUID = "https://www.bellingcat.com/?p=53745"
        const val POST_URL = "https://www.bellingcat.com/news/2026/09/01/the-ship/"
        const val KEEPER_SAVED_AT = 7_007L
        const val COPY_READ_AT = 600L
        const val COPY_SCROLL = 1_234
    }
}
