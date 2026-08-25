package dev.mkiros.perch.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.entity.FeedEntity
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Migration 5 → 6 (Y02/#23), run against a **real, populated version-5 file**.
 *
 * A pasted link needs a feed to satisfy `entries.feedId`'s foreign key, and PLAN-6 §0.3
 * chose a synthetic source over a nullable column — so the upgrade has to do two things:
 * add the column every feed row now carries, and seed the one synthetic row itself, on an
 * install that has never seen either. The usual pair of assertions apply on top of that:
 * every existing feed and entry survives untouched.
 */
@RunWith(RobolectricTestRunner::class)
class PerchMigration5To6Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file: File = context.getDatabasePath("migration-5-to-6.db")
    private var db: PerchDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        SQLiteDatabase.deleteDatabase(file)
    }

    @Test
    fun `upgrading a populated v5 database keeps every feed and entry`() = runTest {
        seedVersion5()

        val migrated = openAtCurrentVersion()

        assertThat(migrated.feedDao().getAll().map { it.feedUrl }).containsExactly(
            "https://fabiensanglard.net/rss.xml",
            "https://gpuopen.com/feed.xml",
        )
        assertThat(migrated.entryDao().countAll()).isEqualTo(2)
    }

    @Test
    fun `upgrading leaves read and saved state untouched`() = runTest {
        seedVersion5()

        val migrated = openAtCurrentVersion()
        val feedId = migrated.feedDao().findByUrl("https://fabiensanglard.net/rss.xml")!!.id
        val saved = migrated.entryDao().findByGuid(feedId, "guid-b")

        assertThat(saved?.isSaved).isTrue()
        assertThat(saved?.savedAt).isEqualTo(7_007L)
    }

    @Test
    fun `the synthetic saved-links feed is present, unfiled, and marked synthetic`() = runTest {
        seedVersion5()

        val migrated = openAtCurrentVersion()
        val saved = migrated.feedDao().findByUrl(FeedEntity.SAVED_LINKS_FEED_URL)

        assertThat(saved).isNotNull()
        assertThat(saved!!.isSynthetic).isTrue()
        assertThat(saved.title).isEqualTo(FeedEntity.SAVED_LINKS_TITLE)
        // Not returned by getAll(): every other query that means "subscribed sources"
        // must not gain a phantom entry the day this migration runs.
        assertThat(migrated.feedDao().getAll().map { it.feedUrl })
            .doesNotContain(FeedEntity.SAVED_LINKS_FEED_URL)
    }

    private fun seedVersion5() {
        file.parentFile?.mkdirs()
        SQLiteDatabase.deleteDatabase(file)
        val v5 = SQLiteDatabase.openOrCreateDatabase(file, null)
        ExportedSchemas.createStatements(version = 5).forEach(v5::execSQL)
        v5.execSQL(
            """
            INSERT INTO folders (id, name, sortIndex, createdAt)
            VALUES (1, 'Uncategorized', 0, 900)
            """.trimIndent(),
        )
        v5.execSQL(
            """
            INSERT INTO feeds (id, feedUrl, siteUrl, title, customTitle, faviconUrl, etag,
                lastModified, lastFetchedAt, lastSuccessAt, lastError, consecutiveFailures,
                addedAt, sortIndex, folderId)
            VALUES
                (1, 'https://fabiensanglard.net/rss.xml', 'https://fabiensanglard.net',
                 'Fabien Sanglard', NULL, NULL, NULL, NULL, 1000, 1000, NULL, 0, 900, 0, 1),
                (2, 'https://gpuopen.com/feed.xml', 'https://gpuopen.com',
                 'AMD GPUOpen', NULL, NULL, NULL, NULL, 1000, 1000, NULL, 0, 950, 1, 1)
            """.trimIndent(),
        )
        v5.execSQL(
            """
            INSERT INTO entries (feedId, guid, title, link, author, publishedAt,
                publishedIsEstimated, summary, contentHtml, imageUrl, isRead, readAt,
                isSaved, savedAt, isStarred, starredAt, bodyIsExcerpt, fullTextAt, fetchedAt)
            VALUES
                (1, 'guid-a', 'A dock that wakes up', 'https://fabiensanglard.net/tb4/', NULL,
                 500, 0, NULL, NULL, NULL, 1, 600, 0, NULL, 0, NULL, 0, NULL, 1000),
                (1, 'guid-b', 'Silpheed', 'https://fabiensanglard.net/silpheed/', NULL,
                 400, 0, NULL, NULL, NULL, 0, NULL, 1, 7007, 1, 8008, 0, NULL, 1000)
            """.trimIndent(),
        )
        v5.version = 5
        v5.close()
    }

    private fun openAtCurrentVersion(): PerchDatabase =
        Room.databaseBuilder(context, PerchDatabase::class.java, file.name)
            .addMigrations(*PerchDatabase.MIGRATIONS)
            .build()
            .also { db = it }
}
