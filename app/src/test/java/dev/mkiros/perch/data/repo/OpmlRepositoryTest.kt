package dev.mkiros.perch.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.FeedDao
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.FeedEntity
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
 * export → import unchanged, because that is the promise an export makes. The rest is
 * about an import being safe to run twice — a source already subscribed to is counted,
 * never re-added and never re-fetched, so importing yesterday's file back over today's
 * library costs nothing and loses nothing.
 */
@RunWith(RobolectricTestRunner::class)
class OpmlRepositoryTest {

    private lateinit var db: PerchDatabase
    private lateinit var feeds: FeedDao
    private lateinit var repo: OpmlRepository

    private val now = Instant.parse("2026-08-07T12:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        db = newDatabase()
        feeds = db.feedDao()
        repo = OpmlRepository(feeds, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC))
    }

    @After
    fun tearDown() = db.close()

    private fun newDatabase() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        PerchDatabase::class.java,
    ).build()

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

    @Test
    fun `exporting every source and importing it back yields the same source set`() = runTest {
        repeat(42) { feeds.insert(feed(it)) }
        val before = feeds.sourceSet()

        val exported = repo.export()

        val fresh = newDatabase()
        val imported = OpmlRepository(fresh.feedDao(), Clock.systemUTC()).import(exported)

        assertThat(imported).isEqualTo(OpmlImportResult.Imported(added = 42, duplicates = 0, invalid = 0))
        assertThat(fresh.feedDao().sourceSet()).isEqualTo(before)
        fresh.close()
    }

    @Test
    fun `export labels a renamed source the way the drawer does`() = runTest {
        feeds.insert(feed(1).copy(title = "Feed's own title", customTitle = "My name for it"))

        assertThat(repo.export()).contains("""text="My name for it"""")
        assertThat(repo.export()).doesNotContain("Feed's own title")
    }

    @Test
    fun `importing a nested export from another reader flattens it`() = runTest {
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

        assertThat(result).isEqualTo(OpmlImportResult.Imported(added = 3, duplicates = 0, invalid = 0))
        assertThat(feeds.getAll().map { it.feedUrl })
            .containsExactly("https://a.example/feed", "https://b.example/feed", "https://c.example/feed")
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

        assertThat(result).isEqualTo(OpmlImportResult.Imported(added = 1, duplicates = 1, invalid = 0))
        assertThat(feeds.getAll()).hasSize(2)
        assertThat(feeds.findByUrl("https://source1.example/feed.xml")!!.customTitle)
            .isEqualTo("My name for it")
    }

    @Test
    fun `a source listed twice in one file is imported once and counted once`() = runTest {
        val result = repo.import(
            """
            <opml version="2.0"><body>
              <outline text="Folder one"><outline type="rss" text="A" xmlUrl="https://a.example/feed"/></outline>
              <outline text="Folder two"><outline type="rss" text="A again" xmlUrl="https://a.example/feed"/></outline>
            </body></opml>
            """.trimIndent(),
        )

        assertThat(result).isEqualTo(OpmlImportResult.Imported(added = 1, duplicates = 1, invalid = 0))
        assertThat(feeds.getAll()).hasSize(1)
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

        assertThat(result).isEqualTo(OpmlImportResult.Imported(added = 1, duplicates = 0, invalid = 1))
        assertThat(feeds.getAll()).hasSize(1)
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
