package dev.mkiros.perch.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.db.entity.FolderEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Folders at the storage layer (PLAN-2 §0): every source belongs to exactly one, deleting
 * one never deletes a source, and the unread count per folder comes out of SQL rather
 * than out of a fold in Kotlin.
 */
@RunWith(RobolectricTestRunner::class)
class FolderDaoTest {

    private lateinit var db: PerchDatabase
    private lateinit var folders: FolderDao
    private lateinit var feeds: FeedDao
    private lateinit var entries: EntryDao

    @Before
    fun openDatabase() {
        db = PerchDatabase.inMemory(ApplicationProvider.getApplicationContext())
        folders = db.folderDao()
        feeds = db.feedDao()
        entries = db.entryDao()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    @Test
    fun `a fresh database already holds Uncategorized as folder one`() = runTest {
        val all = folders.getAll()

        assertThat(all.map { it.id }).containsExactly(FolderEntity.UNCATEGORIZED_ID)
        assertThat(all.single().name).isEqualTo(FolderEntity.UNCATEGORIZED_NAME)
    }

    @Test
    fun `a source added without a folder lands in Uncategorized`() = runTest {
        val feedId = feeds.insert(feed("https://example.com/feed.xml"))

        assertThat(feeds.findById(feedId)?.folderId).isEqualTo(FolderEntity.UNCATEGORIZED_ID)
    }

    @Test
    fun `two folders cannot share a name`() = runTest {
        folders.insert(folder("Graphics"))

        try {
            folders.insert(folder("Graphics"))
            throw AssertionError("expected the unique name index to reject the duplicate")
        } catch (expected: SQLiteConstraintException) {
            assertThat(expected).hasMessageThat().contains("folders.name")
        }
    }

    @Test
    fun `deleting a folder reassigns its sources instead of deleting them`() = runTest {
        val graphics = folders.insert(folder("Graphics"))
        val feedId = feeds.insert(feed("https://example.com/feed.xml", folderId = graphics))

        folders.deleteAndReassign(graphics)

        assertThat(folders.getAll().map { it.id }).containsExactly(FolderEntity.UNCATEGORIZED_ID)
        assertThat(feeds.findById(feedId)?.folderId).isEqualTo(FolderEntity.UNCATEGORIZED_ID)
    }

    @Test
    fun `folders are observed in sort order with Uncategorized last`() = runTest {
        folders.insert(folder("Security", sortIndex = 2))
        folders.insert(folder("Graphics", sortIndex = 1))

        val observed = folders.observeAll().first()

        assertThat(observed.map { it.name })
            .containsExactly("Graphics", "Security", FolderEntity.UNCATEGORIZED_NAME)
            .inOrder()
    }

    @Test
    fun `a fully read folder is absent from the unread counts rather than zero`() = runTest {
        val graphics = folders.insert(folder("Graphics"))
        val quiet = feeds.insert(feed("https://quiet.example/feed.xml", folderId = graphics))
        val loud = feeds.insert(feed("https://loud.example/feed.xml"))
        entries.insert(entry(quiet, "read-1", isRead = true))
        entries.insert(entry(loud, "unread-1", isRead = false))
        entries.insert(entry(loud, "unread-2", isRead = false))

        val counts = folders.observeUnreadCountsByFolder().first()

        assertThat(counts).containsExactly(FolderEntity.UNCATEGORIZED_ID, 2)
        assertThat(counts[graphics]).isNull()
    }

    private fun folder(name: String, sortIndex: Int = 0) =
        FolderEntity(name = name, sortIndex = sortIndex, createdAt = 0)

    private fun feed(feedUrl: String, folderId: Long = FolderEntity.UNCATEGORIZED_ID) =
        FeedEntity(
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
            folderId = folderId,
        )

    private fun entry(feedId: Long, guid: String, isRead: Boolean) =
        EntryEntity(
            feedId = feedId,
            guid = guid,
            title = guid,
            link = null,
            author = null,
            publishedAt = 0,
            publishedIsEstimated = false,
            summary = null,
            contentHtml = null,
            imageUrl = null,
            isRead = isRead,
            readAt = if (isRead) 1 else null,
            fetchedAt = 0,
        )
}
