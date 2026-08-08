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
 * Migration 1 → 2 (U03), run against a **real, populated version-1 file**.
 *
 * v0.1 is installed on a real phone, so this is the only test that answers the question
 * that actually matters about folders: does the upgrade keep what is already there? The
 * v1 database is built from `app/schemas/1.json` rather than from a hand-copied DDL, so
 * it is the schema that shipped and cannot quietly drift from it. Room itself supplies
 * the other half of the assertion — opening the migrated file validates the result
 * against the version-2 entities and throws if the migration produced a different shape.
 */
@RunWith(RobolectricTestRunner::class)
class PerchMigration1To2Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file: File = context.getDatabasePath("migration-1-to-2.db")
    private var db: PerchDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        SQLiteDatabase.deleteDatabase(file)
    }

    @Test
    fun `upgrading a populated v1 database keeps every feed and entry`() = runTest {
        seedVersion1()

        val migrated = openAtCurrentVersion()

        assertThat(migrated.feedDao().getAll().map { it.feedUrl }).containsExactly(
            "https://fabiensanglard.net/rss.xml",
            "https://nullprogram.com/feed/",
        )
        assertThat(migrated.entryDao().countAll()).isEqualTo(3)
    }

    @Test
    fun `upgrading files every existing source under Uncategorized`() = runTest {
        seedVersion1()

        val migrated = openAtCurrentVersion()

        assertThat(migrated.feedDao().getAll().map { it.folderId })
            .containsExactly(FolderEntity.UNCATEGORIZED_ID, FolderEntity.UNCATEGORIZED_ID)
        val folders = migrated.folderDao().getAll()
        assertThat(folders.map { it.id }).containsExactly(FolderEntity.UNCATEGORIZED_ID)
        assertThat(folders.single().name).isEqualTo(FolderEntity.UNCATEGORIZED_NAME)
    }

    @Test
    fun `upgrading leaves read and starred state alone`() = runTest {
        seedVersion1()

        val migrated = openAtCurrentVersion()

        val entries = migrated.entryDao()
        assertThat(entries.findByGuid(feedId = 1, guid = "guid-a")?.isRead).isTrue()
        assertThat(entries.findByGuid(feedId = 1, guid = "guid-a")?.readAt).isEqualTo(600)
        assertThat(entries.findByGuid(feedId = 1, guid = "guid-b")?.isStarred).isTrue()
        assertThat(entries.unreadIds(feedId = null, folderId = null)).hasSize(2)
    }

    /** Builds the shipped v1 schema, fills it with rows a real install would have, closes it. */
    private fun seedVersion1() {
        file.parentFile?.mkdirs()
        SQLiteDatabase.deleteDatabase(file)
        val v1 = SQLiteDatabase.openOrCreateDatabase(file, null)
        ExportedSchemas.createStatements(version = 1).forEach(v1::execSQL)
        v1.execSQL(
            """
            INSERT INTO feeds (id, feedUrl, siteUrl, title, customTitle, faviconUrl, etag,
                lastModified, lastFetchedAt, lastSuccessAt, lastError, consecutiveFailures,
                addedAt, sortIndex)
            VALUES
                (1, 'https://fabiensanglard.net/rss.xml', 'https://fabiensanglard.net',
                 'Fabien Sanglard', NULL, NULL, 'W/"abc"', NULL, 1000, 1000, NULL, 0, 900, 0),
                (2, 'https://nullprogram.com/feed/', 'https://nullprogram.com',
                 'null program', 'Chris Wellons', NULL, NULL, NULL, 1000, 1000, NULL, 0, 950, 1)
            """.trimIndent(),
        )
        v1.execSQL(
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
        v1.version = 1
        v1.close()
    }

    /**
     * Opens the seeded file with the shipped migrations. Room validates the migrated
     * schema against the current entities here, so a migration that produces the wrong
     * columns, indices or foreign keys fails inside this call rather than on a phone.
     */
    private fun openAtCurrentVersion(): PerchDatabase =
        Room.databaseBuilder(context, PerchDatabase::class.java, file.name)
            .addMigrations(*PerchDatabase.MIGRATIONS)
            .build()
            .also { db = it }
}
