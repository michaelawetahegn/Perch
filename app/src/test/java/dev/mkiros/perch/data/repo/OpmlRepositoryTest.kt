package dev.mkiros.perch.data.repo

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.FeedDao
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.db.entity.FolderEntity
import java.io.File
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
 * Subscriptions leaving and entering the app (SPEC.md §9).
 *
 * The standing assertion is the round trip: everything the drawer lists must survive
 * export → import unchanged, because that is the promise an export makes. Since U13 that
 * includes the folder a source is filed under, so the round trip compares membership and
 * not just the source set. The rest is about an import being safe to run twice — a source
 * already subscribed to is counted, never re-added and never re-fetched, so importing
 * yesterday's file back over today's library costs nothing and loses nothing.
 */
@RunWith(RobolectricTestRunner::class)
class OpmlRepositoryTest {

    private lateinit var db: PerchDatabase
    private lateinit var feeds: FeedDao
    private lateinit var folders: FolderRepository
    private lateinit var repo: OpmlRepository

    private val now = Instant.parse("2026-08-07T12:00:00Z").toEpochMilli()
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)

    @Before
    fun setUp() {
        db = newDatabase()
        feeds = db.feedDao()
        folders = FolderRepository(db.folderDao(), clock)
        repo = OpmlRepository(feeds, folders, clock)
    }

    @After
    fun tearDown() = db.close()

    private fun newDatabase() = PerchDatabase.inMemory(ApplicationProvider.getApplicationContext())

    private fun PerchDatabase.repo() = OpmlRepository(feedDao(), FolderRepository(folderDao(), clock), clock)

    private fun feed(index: Int) = FeedEntity(
        feedUrl = "https://source$index.example/feed.xml",
        siteUrl = if (index % 3 == 0) null else "https://source$index.example/",
        title = if (index % 5 == 0) "Bits & Bytes #$index" else "Source $index",
        customTitle = null,
        faviconUrl = null,
        etag = "\"etag-$index\"",
        lastModified = null,
        lastFetchedAt = now,
        lastSuccessAt = now,
        lastError = null,
        addedAt = now,
    )

    /** What an export promises to preserve: the label, the address, and the site. */
    private suspend fun FeedDao.sourceSet() =
        getAll().map { Triple(it.customTitle ?: it.title, it.feedUrl, it.siteUrl) }.toSet()

    /** feed address → the name of the folder it is filed under, Uncategorized included. */
    private suspend fun PerchDatabase.membership(): Map<String, String> {
        val names = folderDao().getAll().associate { it.id to it.name }
        return feedDao().getAll().associate { it.feedUrl to names.getValue(it.folderId) }
    }

    private suspend fun folderOf(feedUrl: String): String {
        val feed = feeds.findByUrl(feedUrl)!!
        return folders.findFolder(feed.folderId)!!.name
    }

    @Test
    fun `exporting every source and importing it back yields the same source set`() = runTest {
        repeat(42) { feeds.insert(feed(it)) }
        val before = feeds.sourceSet()

        val exported = repo.export()

        val fresh = newDatabase()
        val imported = fresh.repo().import(exported)

        assertThat(imported)
            .isEqualTo(OpmlImportResult.Imported(added = 42, duplicates = 0, invalid = 0, folders = 0))
        assertThat(fresh.feedDao().sourceSet()).isEqualTo(before)
        fresh.close()
    }

    /**
     * U13's contract, over the real corpus rather than a toy: the 42 sources Perch is
     * actually built against, split across four folders with a handful left unfiled,
     * exported, and imported into an empty library. Membership is compared source by
     * source — a round trip that reproduces the folders but shuffles who is in them is
     * the failure this exists to catch.
     */
    @Test
    fun `the 42-source manifest survives the round trip with its folders and membership`() = runTest {
        val addresses = File(repoRoot(), "fixtures/manifest.tsv").readLines()
            .filter { it.isNotBlank() }.map { it.split('\t')[1] }
        assertThat(addresses).hasSize(42)

        val names = listOf("Systems", "Security", "Graphics", "AI/LLM")
        addresses.forEachIndexed { index, address ->
            // Every fifth source is left unfiled, so the export exercises both shapes.
            val folderId =
                if (index % 5 == 4) FolderEntity.UNCATEGORIZED_ID
                else folders.createFolder(names[index % names.size])
            feeds.insert(feed(index).copy(feedUrl = address, folderId = folderId))
        }
        val before = db.membership()
        assertThat(before.values.toSet()).hasSize(names.size + 1)

        val exported = repo.export()

        val fresh = newDatabase()
        val imported = fresh.repo().import(exported)

        assertThat(imported)
            .isEqualTo(OpmlImportResult.Imported(added = 42, duplicates = 0, invalid = 0, folders = 4))
        assertThat(fresh.membership()).isEqualTo(before)
        assertThat(fresh.feedDao().sourceSet()).isEqualTo(feeds.sourceSet())
        fresh.close()
    }

    @Test
    fun `export nests a folder's sources and leaves Uncategorized at top level`() = runTest {
        val graphics = folders.createFolder("Graphics")
        feeds.insert(feed(1).copy(folderId = graphics))
        feeds.insert(feed(2))

        val exported = repo.export()

        assertThat(exported).contains("""<outline text="Graphics" title="Graphics">""")
        assertThat(exported).doesNotContain("Uncategorized")
    }

    /**
     * The shape the plan names: a file from another reader, two folders deep in places,
     * with sources sitting at top level beside the containers and one outline pointing
     * nowhere. Nothing may be lost, and nothing may land in a folder called "Systems".
     */
    @Test
    fun `an export from another reader imports without loss`() = runTest {
        val result = repo.import(File(repoRoot(), "fixtures/opml/other-reader.opml").readText())

        assertThat(result)
            .isEqualTo(OpmlImportResult.Imported(added = 6, duplicates = 0, invalid = 1, folders = 2))
        assertThat(db.membership()).containsExactlyEntriesIn(
            mapOf(
                "https://daringfireball.net/feeds/main" to "Uncategorized",
                "https://nullprogram.com/feed/" to "Programming",
                "https://blog.regehr.org/feed" to "Programming",
                "https://blog.llvm.org/index.xml" to "Programming",
                "https://googleprojectzero.blogspot.com/feeds/posts/default" to "Security",
                "https://simonwillison.net/atom/everything/" to "Uncategorized",
            ),
        )
        // The empty container named nothing that had to exist, and neither did "Systems"
        // or "Compilers" — they were flattened onto the folder the drawer will show.
        assertThat(folders.observeFolders().first().map { it.name })
            .containsExactly("Programming", "Security", "Uncategorized")
    }

    @Test
    fun `a folder the library already has is matched case-insensitively rather than duplicated`() = runTest {
        folders.createFolder("Graphics")

        val result = repo.import(
            """
            <opml version="2.0"><body>
              <outline text="graphics"><outline type="rss" text="A" xmlUrl="https://a.example/feed"/></outline>
            </body></opml>
            """.trimIndent(),
        )

        assertThat(result)
            .isEqualTo(OpmlImportResult.Imported(added = 1, duplicates = 0, invalid = 0, folders = 0))
        assertThat(folderOf("https://a.example/feed")).isEqualTo("Graphics")
        assertThat(folders.observeFolders().first().map { it.name })
            .containsExactly("Graphics", "Uncategorized")
    }

    /**
     * Re-importing yesterday's file must cost nothing, and an empty folder is a cost —
     * it is a row in the drawer the reader has to go and delete. So a folder is created
     * at the moment a source is actually filed under it, never in advance.
     */
    @Test
    fun `re-importing the same file a second time creates neither sources nor folders`() = runTest {
        val file = File(repoRoot(), "fixtures/opml/other-reader.opml").readText()
        repo.import(file)

        val again = repo.import(file)

        assertThat(again)
            .isEqualTo(OpmlImportResult.Imported(added = 0, duplicates = 6, invalid = 1, folders = 0))
        assertThat(folders.observeFolders().first()).hasSize(3)
    }

    @Test
    fun `export labels a renamed source the way the drawer does`() = runTest {
        feeds.insert(feed(1).copy(title = "Feed's own title", customTitle = "My name for it"))

        assertThat(repo.export()).contains("""text="My name for it"""")
        assertThat(repo.export()).doesNotContain("Feed's own title")
    }

    @Test
    fun `nesting deeper than one level is filed under the outermost folder`() = runTest {
        val result = repo.import(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <opml version="1.0"><head><title>subscriptions</title></head><body>
              <outline text="Programming">
                <outline type="rss" text="A" xmlUrl="https://a.example/feed" htmlUrl="https://a.example/"/>
                <outline text="Systems">
                  <outline type="rss" text="B" xmlUrl="https://b.example/feed"/>
                </outline>
              </outline>
              <outline type="rss" text="C" xmlUrl="https://c.example/feed"/>
            </body></opml>
            """.trimIndent(),
        )

        assertThat(result)
            .isEqualTo(OpmlImportResult.Imported(added = 3, duplicates = 0, invalid = 0, folders = 1))
        assertThat(db.membership()).containsExactlyEntriesIn(
            mapOf(
                "https://a.example/feed" to "Programming",
                "https://b.example/feed" to "Programming",
                "https://c.example/feed" to "Uncategorized",
            ),
        )
    }

    @Test
    fun `a source already subscribed to is counted as a duplicate and left alone`() = runTest {
        feeds.insert(feed(1).copy(customTitle = "My name for it"))

        val result = repo.import(
            """
            <opml version="2.0"><body>
              <outline type="rss" text="Renamed elsewhere" xmlUrl="https://source1.example/feed.xml"/>
              <outline type="rss" text="New" xmlUrl="https://new.example/feed"/>
            </body></opml>
            """.trimIndent(),
        )

        assertThat(result)
            .isEqualTo(OpmlImportResult.Imported(added = 1, duplicates = 1, invalid = 0, folders = 0))
        assertThat(feeds.getAll()).hasSize(2)
        assertThat(feeds.findByUrl("https://source1.example/feed.xml")!!.customTitle)
            .isEqualTo("My name for it")
    }

    /**
     * A source already subscribed to is left exactly as it was — including which folder
     * it is in. An import is additive; it does not get to refile a library the reader
     * has already organised.
     */
    @Test
    fun `a duplicate is not moved into the folder the file puts it in`() = runTest {
        val mine = folders.createFolder("Mine")
        feeds.insert(feed(1).copy(folderId = mine))

        val result = repo.import(
            """
            <opml version="2.0"><body>
              <outline text="Theirs">
                <outline type="rss" text="A" xmlUrl="https://source1.example/feed.xml"/>
              </outline>
            </body></opml>
            """.trimIndent(),
        )

        assertThat(result)
            .isEqualTo(OpmlImportResult.Imported(added = 0, duplicates = 1, invalid = 0, folders = 0))
        assertThat(folderOf("https://source1.example/feed.xml")).isEqualTo("Mine")
        assertThat(folders.observeFolders().first().map { it.name })
            .containsExactly("Mine", "Uncategorized")
    }

    @Test
    fun `a source listed twice in one file is imported once, under the first folder`() = runTest {
        val result = repo.import(
            """
            <opml version="2.0"><body>
              <outline text="Folder one"><outline type="rss" text="A" xmlUrl="https://a.example/feed"/></outline>
              <outline text="Folder two"><outline type="rss" text="A again" xmlUrl="https://a.example/feed"/></outline>
            </body></opml>
            """.trimIndent(),
        )

        assertThat(result)
            .isEqualTo(OpmlImportResult.Imported(added = 1, duplicates = 1, invalid = 0, folders = 1))
        assertThat(feeds.getAll()).hasSize(1)
        assertThat(folderOf("https://a.example/feed")).isEqualTo("Folder one")
        // "Folder two" held nothing that was actually imported, so it was never created.
        assertThat(folders.observeFolders().first().map { it.name })
            .containsExactly("Folder one", "Uncategorized")
    }

    @Test
    fun `outlines that point at nothing are counted invalid and the rest still import`() = runTest {
        val result = repo.import(
            """
            <opml version="2.0"><body>
              <outline type="rss" text="Fine" xmlUrl="https://a.example/feed"/>
              <outline type="rss" text="Broken"/>
            </body></opml>
            """.trimIndent(),
        )

        assertThat(result)
            .isEqualTo(OpmlImportResult.Imported(added = 1, duplicates = 0, invalid = 1, folders = 0))
        assertThat(feeds.getAll()).hasSize(1)
    }

    /** Walks up from the working directory, which is `:app` under Gradle and the root elsewhere. */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "fixtures/opml").isDirectory) return dir
            dir = dir.parentFile
        }
        error("fixtures/opml not found")
    }

    @Test
    fun `malformed OPML is a typed error and writes nothing`() = runTest {
        val result = repo.import("<html><body>not your subscriptions</body></html>")

        assertThat(result).isInstanceOf(OpmlImportResult.Malformed::class.java)
        assertThat(feeds.getAll()).isEmpty()
    }

    @Test
    fun `an imported source is left unfetched so the next refresh collects it`() = runTest {
        repo.import("""<opml version="2.0"><body><outline type="rss" text="A" xmlUrl="https://a.example/feed"/></body></opml>""")

        val added = feeds.findByUrl("https://a.example/feed")!!
        assertThat(added.lastFetchedAt).isNull()
        assertThat(added.lastSuccessAt).isNull()
        assertThat(added.etag).isNull()
        assertThat(added.addedAt).isEqualTo(now)
    }
}
