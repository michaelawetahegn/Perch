package dev.mkiros.perch.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.entity.FolderEntity
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Migration 2 → 3 (U04), run against a **real, populated version-2 file**.
 *
 * Same shape as [PerchMigration1To2Test] and for the same reason: v0.1 is on a phone, so
 * the only question worth asking about a schema change is whether the upgrade keeps what
 * is already there. The v2 database is built from `app/schemas/2.json`, so it is the
 * schema that shipped; opening the migrated file with Room supplies the other half of the
 * assertion, because Room validates the result against the version-3 entities.
 */
@RunWith(RobolectricTestRunner::class)
class PerchMigration2To3Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file: File = context.getDatabasePath("migration-2-to-3.db")
    private var db: PerchDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        SQLiteDatabase.deleteDatabase(file)
    }

    @Test
    fun `upgrading a populated v2 database keeps every folder, feed and entry`() = runTest {
        seedVersion2()

        val migrated = openAtCurrentVersion()

        assertThat(migrated.folderDao().getAll().map { it.name })
            .containsExactly(FolderEntity.UNCATEGORIZED_NAME, "Graphics")
        assertThat(migrated.feedDao().getAll().map { it.feedUrl }).containsExactly(
            "https://fabiensanglard.net/rss.xml",
            "https://nullprogram.com/feed/",
        )
        assertThat(migrated.entryDao().countAll()).isEqualTo(3)
    }

    @Test
    fun `upgrading leaves read state alone and starts every entry unsaved`() = runTest {
        seedVersion2()

        val migrated = openAtCurrentVersion()

        val entries = migrated.entryDao()
        val readOne = entries.findByGuid(feedId = 1, guid = "guid-a")
        assertThat(readOne?.isRead).isTrue()
        assertThat(readOne?.readAt).isEqualTo(600)
        assertThat(readOne?.isSaved).isFalse()
        assertThat(readOne?.savedAt).isNull()
        assertThat(entries.unreadIds(feedId = null, folderId = null)).hasSize(2)
    }

    /**
     * `isStarred` shipped in v1 as a column with no UI, so in practice no install has a
     * starred row — but if one did, a null `starredAt` would sort it off the end of the
     * Liked list forever. Backfilling from `fetchedAt` gives it a defensible position
     * instead of a missing one.
     */
    @Test
    fun `an already-starred entry gets a liked timestamp rather than a null one`() = runTest {
        seedVersion2()

        val migrated = openAtCurrentVersion()

        val starred = migrated.entryDao().findByGuid(feedId = 1, guid = "guid-b")
        assertThat(starred?.isStarred).isTrue()
        assertThat(starred?.starredAt).isEqualTo(1000)
        assertThat(migrated.entryDao().findByGuid(feedId = 2, guid = "guid-c")?.starredAt)
            .isNull()
    }

    /** Builds the shipped v2 schema, fills it with rows a real install would have, closes it. */
    private fun seedVersion2() {
        file.parentFile?.mkdirs()
        SQLiteDatabase.deleteDatabase(file)
        val v2 = SQLiteDatabase.openOrCreateDatabase(file, null)
        ExportedSchemas.createStatements(version = 2).forEach(v2::execSQL)
        v2.execSQL(
            """
            INSERT INTO folders (id, name, sortIndex, createdAt)
            VALUES (1, 'Uncategorized', 0, 900), (2, 'Graphics', 1, 950)
            """.trimIndent(),
        )
        v2.execSQL(
            """
            INSERT INTO feeds (id, feedUrl, siteUrl, title, customTitle, faviconUrl, etag,
                lastModified, lastFetchedAt, lastSuccessAt, lastError, consecutiveFailures,
                addedAt, sortIndex, folderId)
            VALUES
                (1, 'https://fabiensanglard.net/rss.xml', 'https://fabiensanglard.net',
                 'Fabien Sanglard', NULL, NULL, 'W/"abc"', NULL, 1000, 1000, NULL, 0, 900, 0, 2),
                (2, 'https://nullprogram.com/feed/', 'https://nullprogram.com',
                 'null program', 'Chris Wellons', NULL, NULL, NULL, 1000, 1000, NULL, 0, 950, 1, 1)
            """.trimIndent(),
        )
        v2.execSQL(
            """
            INSERT INTO entries (feedId, guid, title, link, author, publishedAt,
                publishedIsEstimated, summary, contentHtml, imageUrl, isRead, readAt,
                isStarred, fetchedAt)
            VALUES
                (1, 'guid-a', 'A', 'https://fabiensanglard.net/a', NULL, 500, 0, 's', '<p>a</p>',
                 NULL, 1, 600, 0, 1000),
                (1, 'guid-b', 'B', 'https://fabiensanglard.net/b', NULL, 400, 0, 's', '<p>b</p>',
                 NULL, 0, NULL, 1, 1000),
                (2, 'guid-c', 'C', 'https://nullprogram.com/c', NULL, 300, 0, 's', '<p>c</p>',
                 NULL, 0, NULL, 0, 1000)
            """.trimIndent(),
        )
        v2.version = 2
        v2.close()
    }

    private fun openAtCurrentVersion(): PerchDatabase =
        Room.databaseBuilder(context, PerchDatabase::class.java, file.name)
            .addMigrations(*PerchDatabase.MIGRATIONS)
            .build()
            .also { db = it }
}
