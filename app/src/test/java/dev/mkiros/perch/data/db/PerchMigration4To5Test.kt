package dev.mkiros.perch.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.entity.PendingEntryStateEntity
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Migration 4 → 5 (U14), run against a **real, populated version-4 file**.
 *
 * U14 adds one table and touches nothing else: restored reader state that has no entry to
 * live on yet has to wait somewhere, and `pending_entry_state` is that somewhere. So the
 * assertions are the usual pair — the phone's articles and everything the reader did to
 * them are exactly where they were, and the new table is present and empty, because a
 * migration is not a restore and has nothing to park.
 */
@RunWith(RobolectricTestRunner::class)
class PerchMigration4To5Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file: File = context.getDatabasePath("migration-4-to-5.db")
    private var db: PerchDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        SQLiteDatabase.deleteDatabase(file)
    }

    @Test
    fun `upgrading a populated v4 database keeps every feed and entry`() = runTest {
        seedVersion4()

        val migrated = openAtCurrentVersion()

        assertThat(migrated.feedDao().getAll().map { it.feedUrl }).containsExactly(
            "https://fabiensanglard.net/rss.xml",
            "https://gpuopen.com/feed.xml",
        )
        assertThat(migrated.entryDao().countAll()).isEqualTo(3)
    }

    @Test
    fun `upgrading leaves read, saved and liked state untouched`() = runTest {
        seedVersion4()

        val migrated = openAtCurrentVersion()
        val saved = migrated.entryDao().findByGuid(feedId = 1, guid = "guid-b")

        assertThat(saved?.isSaved).isTrue()
        assertThat(saved?.savedAt).isEqualTo(7_007L)
        assertThat(saved?.isStarred).isTrue()
        assertThat(saved?.starredAt).isEqualTo(8_008L)
        assertThat(migrated.entryDao().findByGuid(feedId = 1, guid = "guid-a")?.readAt)
            .isEqualTo(600L)
    }

    @Test
    fun `the parking table arrives empty and usable`() = runTest {
        seedVersion4()

        val migrated = openAtCurrentVersion()

        assertThat(migrated.entryDao().countPendingState()).isEqualTo(0)
        migrated.entryDao().upsertPendingState(
            listOf(
                PendingEntryStateEntity(
                    feedUrl = "https://gpuopen.com/feed.xml",
                    guid = "not-yet-fetched",
                    isRead = true,
                    readAt = 1_234L,
                    isSaved = false,
                    savedAt = null,
                    isStarred = false,
                    starredAt = null,
                ),
            ),
        )
        assertThat(migrated.entryDao().countPendingState()).isEqualTo(1)
    }

    private fun seedVersion4() {
        file.parentFile?.mkdirs()
        SQLiteDatabase.deleteDatabase(file)
        val v4 = SQLiteDatabase.openOrCreateDatabase(file, null)
        ExportedSchemas.createStatements(version = 4).forEach(v4::execSQL)
        v4.execSQL(
            """
            INSERT INTO folders (id, name, sortIndex, createdAt)
            VALUES (1, 'Uncategorized', 0, 900), (2, 'Graphics', 1, 950)
            """.trimIndent(),
        )
        v4.execSQL(
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
        v4.execSQL(
            """
            INSERT INTO entries (feedId, guid, title, link, author, publishedAt,
                publishedIsEstimated, summary, contentHtml, imageUrl, isRead, readAt,
                isSaved, savedAt, isStarred, starredAt, bodyIsExcerpt, fullTextAt, fetchedAt)
            VALUES
                (1, 'guid-a', 'A dock that wakes up', 'https://fabiensanglard.net/tb4/', NULL,
                 500, 0, NULL, NULL, NULL, 1, 600, 0, NULL, 0, NULL, 0, NULL, 1000),
                (1, 'guid-b', 'Silpheed', 'https://fabiensanglard.net/silpheed/', NULL,
                 400, 0, NULL, NULL, NULL, 0, NULL, 1, 7007, 1, 8008, 0, NULL, 1000),
                (2, 'guid-c', 'Adaptive subdivision', 'https://gpuopen.com/learn/one/', NULL,
                 300, 0, 'A 194 character teaser.', '<p>A 194 character teaser.</p>', NULL,
                 0, NULL, 0, NULL, 0, NULL, 1, NULL, 1000)
            """.trimIndent(),
        )
        v4.version = 4
        v4.close()
    }

    private fun openAtCurrentVersion(): PerchDatabase =
        Room.databaseBuilder(context, PerchDatabase::class.java, file.name)
            .addMigrations(*PerchDatabase.MIGRATIONS)
            .build()
            .also { db = it }
}
