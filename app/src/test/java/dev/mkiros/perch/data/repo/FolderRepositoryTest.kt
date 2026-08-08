package dev.mkiros.perch.data.repo

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.FeedDao
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.db.entity.FolderEntity
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The folder contract the drawer will be written against (PLAN-2 §0 and U06).
 *
 * Deleting a folder is the assertion that matters: it is the one operation a user can
 * reach for that could plausibly take forty subscriptions with it, and it must never do
 * anything but move them.
 */
@RunWith(RobolectricTestRunner::class)
class FolderRepositoryTest {

    private lateinit var db: PerchDatabase
    private lateinit var feedDao: FeedDao
    private lateinit var repo: FolderRepository

    private val now = Instant.parse("2026-08-07T12:00:00Z")

    @Before
    fun setUp() {
        db = PerchDatabase.inMemory(ApplicationProvider.getApplicationContext())
        feedDao = db.feedDao()
        repo = FolderRepository(
            folderDao = db.folderDao(),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a created folder is stamped and appended after the existing ones`() = runTest {
        val graphics = repo.createFolder("Graphics")
        val security = repo.createFolder("Security")

        val folders = repo.observeFolders().first()
        assertThat(folders.map { it.name })
            .containsExactly("Graphics", "Security", FolderEntity.UNCATEGORIZED_NAME)
            .inOrder()
        assertThat(folders.first { it.id == graphics }.createdAt).isEqualTo(now.toEpochMilli())
        assertThat(folders.first { it.id == security }.sortIndex)
            .isGreaterThan(folders.first { it.id == graphics }.sortIndex)
    }

    @Test
    fun `creating a folder that already exists returns the existing one, whatever its case`() =
        runTest {
            val first = repo.createFolder("Graphics")

            val again = repo.createFolder("  graphics ")

            assertThat(again).isEqualTo(first)
            assertThat(repo.observeFolders().first()).hasSize(2)
        }

    @Test
    fun `a folder cannot be created with a blank name`() = runTest {
        try {
            repo.createFolder("   ")
            throw AssertionError("expected a blank folder name to be refused")
        } catch (expected: IllegalArgumentException) {
            assertThat(expected).hasMessageThat().contains("name")
        }
    }

    @Test
    fun `renaming a folder keeps its sources`() = runTest {
        val graphics = repo.createFolder("Graphics")
        val feedId = addSource("https://example.com/feed.xml", graphics)

        assertThat(repo.renameFolder(graphics, "Graphics & GPUs")).isTrue()

        assertThat(repo.findFolder(graphics)?.name).isEqualTo("Graphics & GPUs")
        assertThat(feedDao.findById(feedId)?.folderId).isEqualTo(graphics)
    }

    @Test
    fun `deleting a folder moves its sources to Uncategorized and keeps every one of them`() =
        runTest {
            val graphics = repo.createFolder("Graphics")
            val kept = listOf(
                addSource("https://a.example/feed.xml", graphics),
                addSource("https://b.example/feed.xml", graphics),
            )

            assertThat(repo.deleteFolder(graphics)).isTrue()

            assertThat(feedDao.getAll().map { it.id }).containsExactlyElementsIn(kept)
            assertThat(feedDao.getAll().map { it.folderId })
                .containsExactly(FolderEntity.UNCATEGORIZED_ID, FolderEntity.UNCATEGORIZED_ID)
            assertThat(repo.findFolder(graphics)).isNull()
        }

    @Test
    fun `Uncategorized can be neither renamed nor deleted`() = runTest {
        assertThat(repo.deleteFolder(FolderEntity.UNCATEGORIZED_ID)).isFalse()
        assertThat(repo.renameFolder(FolderEntity.UNCATEGORIZED_ID, "Inbox")).isFalse()

        assertThat(repo.findFolder(FolderEntity.UNCATEGORIZED_ID)?.name)
            .isEqualTo(FolderEntity.UNCATEGORIZED_NAME)
    }

    @Test
    fun `moving a source changes its folder and nothing else`() = runTest {
        val graphics = repo.createFolder("Graphics")
        val feedId = addSource("https://example.com/feed.xml", FolderEntity.UNCATEGORIZED_ID)
        val before = feedDao.findById(feedId)

        repo.moveSource(feedId, graphics)

        assertThat(feedDao.findById(feedId)).isEqualTo(before?.copy(folderId = graphics))
    }

    @Test
    fun `unread counts are grouped per folder and omit a folder with nothing unread`() = runTest {
        val graphics = repo.createFolder("Graphics")
        val quiet = addSource("https://quiet.example/feed.xml", graphics)
        val loud = addSource("https://loud.example/feed.xml", FolderEntity.UNCATEGORIZED_ID)
        db.entryDao().insert(entry(quiet, "read", isRead = true))
        db.entryDao().insert(entry(loud, "unread", isRead = false))

        val counts = repo.observeUnreadCountsByFolder().first()

        assertThat(counts[FolderEntity.UNCATEGORIZED_ID]).isEqualTo(1)
        assertThat(counts[graphics]).isNull()
    }

    private suspend fun addSource(feedUrl: String, folderId: Long): Long = feedDao.insert(
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
        ),
    )

    private fun entry(feedId: Long, guid: String, isRead: Boolean) =
        dev.mkiros.perch.data.db.entity.EntryEntity(
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
