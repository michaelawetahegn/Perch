package dev.mkiros.perch.data.repo

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.EntryDao
import dev.mkiros.perch.data.db.FeedDao
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.document.DocumentFixtures
import dev.mkiros.perch.data.document.DocumentStore
import dev.mkiros.perch.data.net.FeedFetcher
import dev.mkiros.perch.support.FixtureRasterizer
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Y03: a link the reader pastes becomes a row on the synthetic saved-links feed (§0.3/§0.4)
 * without ever subscribing to its site. Every disappointment is a [SaveLinkFailure] carried
 * in [Result.failure], never a thrown exception.
 */
@RunWith(RobolectricTestRunner::class)
class SavedLinkRepositoryTest {

    @get:Rule
    val tmpDir: TemporaryFolder = TemporaryFolder()

    private lateinit var db: PerchDatabase
    private lateinit var feeds: FeedDao
    private lateinit var entries: EntryDao
    private lateinit var server: MockWebServer
    private lateinit var repo: SavedLinkRepository
    private lateinit var documents: DocumentStore

    /** What the content resolver would hand back for each shared URI (§0.8's map). */
    private val shared = mutableMapOf<Uri, () -> InputStream>()

    private val now = Instant.parse("2026-08-25T12:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        db = PerchDatabase.inMemory(ApplicationProvider.getApplicationContext())
        feeds = db.feedDao()
        entries = db.entryDao()
        server = MockWebServer()
        server.start()
        documents = DocumentStore(File(tmpDir.root, "documents"))
        repo = SavedLinkRepository(
            feedDao = feeds,
            entryDao = entries,
            fetcher = FeedFetcher(
                OkHttpClient.Builder().readTimeout(500, TimeUnit.MILLISECONDS).build(),
            ),
            clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
            documents = documents,
            rasterizer = FixtureRasterizer(),
            documentOpener = DocumentOpener { uri -> shared[uri]?.invoke() },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    @Test
    fun `a pasted article lands on the saved-links feed with its title, date and body`() = runTest {
        server.enqueue(article())

        val result = repo.saveLink(server.url("/post").toString())

        assertThat(result.isSuccess).isTrue()
        val saved = entries.findById(result.getOrThrow())!!
        assertThat(saved.title).isEqualTo("An Article")
        assertThat(saved.isSaved).isTrue()
        assertThat(saved.savedAt).isEqualTo(now)
        assertThat(saved.publishedIsEstimated).isFalse()
        assertThat(saved.contentHtml).contains("prose")
        val savedFeed = feeds.findById(saved.feedId)!!
        assertThat(savedFeed.feedUrl).isEqualTo(FeedEntity.SAVED_LINKS_FEED_URL)
    }

    @Test
    fun `pasting the same link twice does not duplicate and does not error`() = runTest {
        server.enqueue(article())
        server.enqueue(article())

        val first = repo.saveLink(server.url("/post").toString())
        val second = repo.saveLink(server.url("/post").toString())

        assertThat(first.isSuccess).isTrue()
        assertThat(second.isSuccess).isTrue()
        assertThat(second.getOrThrow()).isEqualTo(first.getOrThrow())
        assertThat(entries.countAll()).isEqualTo(1)
    }

    @Test
    fun `pasting a link that was removed from To-Read puts it back on the queue`() = runTest {
        server.enqueue(article())
        server.enqueue(article())

        val id = repo.saveLink(server.url("/post").toString()).getOrThrow()
        // The reader swipes it off To-Read, then pastes the same address again.
        entries.setSaved(id, isSaved = false, savedAt = null)

        val again = repo.saveLink(server.url("/post").toString())

        assertThat(again.getOrThrow()).isEqualTo(id)
        val row = entries.findById(id)!!
        assertThat(row.isSaved).isTrue()
        assertThat(row.savedAt).isEqualTo(now)
    }

    @Test
    fun `a link that is really a feed is not saved, and says so`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody(
                    """
                    <?xml version="1.0"?>
                    <rss version="2.0"><channel><title>A Feed</title>
                    <item><guid>a1</guid><title>One</title></item>
                    </channel></rss>
                    """.trimIndent(),
                )
                .addHeader("Content-Type", "application/rss+xml"),
        )

        val result = repo.saveLink(server.url("/feed.xml").toString())

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SaveLinkFailure.IsFeed::class.java)
        assertThat(entries.countAll()).isEqualTo(0)
    }

    @Test
    fun `an unreachable address fails with a reason, not an exception`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val result = repo.saveLink(server.url("/post").toString())

        assertThat(result.isFailure).isTrue()
        val failure = result.exceptionOrNull() as SaveLinkFailure.Unreachable
        assertThat(failure.message?.lowercase()).contains("timed out")
    }

    @Test
    fun `a page over the eight mebibyte cap fails with a reason, not an exception`() = runTest {
        val oversized = Buffer().write(ByteArray(9 * 1024 * 1024))
        server.enqueue(MockResponse().setBody(oversized))

        val result = repo.saveLink(server.url("/post").toString())

        assertThat(result.isFailure).isTrue()
        val failure = result.exceptionOrNull() as SaveLinkFailure.Unreachable
        assertThat(failure.message?.lowercase()).contains("too large")
    }

    @Test
    fun `a page with no metadata still saves, under its own address`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("<html><body><p>no head, no title, no date here</p></body></html>")
                .addHeader("Content-Type", "text/html; charset=utf-8"),
        )

        val result = repo.saveLink(server.url("/bare").toString())

        assertThat(result.isSuccess).isTrue()
        val saved = entries.findById(result.getOrThrow())!!
        assertThat(saved.title).isEqualTo(server.url("/bare").toString())
        assertThat(saved.publishedIsEstimated).isTrue()
        assertThat(saved.publishedAt).isEqualTo(now)
    }

    @Test
    fun `a page with no extractable body still saves its title and link`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody(
                    """
                    <html><head><title>Just A Title</title></head>
                    <body><nav>menu</nav></body></html>
                    """.trimIndent(),
                )
                .addHeader("Content-Type", "text/html; charset=utf-8"),
        )

        val result = repo.saveLink(server.url("/stub").toString())

        assertThat(result.isSuccess).isTrue()
        val saved = entries.findById(result.getOrThrow())!!
        assertThat(saved.title).isEqualTo("Just A Title")
        assertThat(saved.contentHtml).isNull()
        assertThat(saved.link).isEqualTo(server.url("/stub").toString())
    }

    @Test
    fun `a pasted PDF is stored whole and titled from the file`() = runTest {
        val fixture = DocumentFixtures.manifest().first { it.slug == "ssrn-6191618" }
        val pdfContent = fixture.file.readBytes()
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(pdfContent))
                .addHeader("Content-Type", "application/pdf")
                .addHeader("Content-Disposition", "attachment; filename=\"sample.pdf\""),
        )

        val result = repo.saveLink(server.url("/doc.pdf").toString())

        assertThat(result.isSuccess).isTrue()
        val saved = entries.findById(result.getOrThrow())!!
        assertThat(saved.documentPath).isNotNull()
        assertThat(saved.contentHtml).isNull()
        assertThat(saved.summary).isNull()
        assertThat(saved.isSaved).isTrue()
    }

    @Test
    fun `a pasted PDF keeps the top square of its first page as its thumbnail`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(fixture("ssrn-6191618").file.readBytes()))
                .addHeader("Content-Type", "application/pdf"),
        )

        val saved = entries.findById(repo.saveLink(server.url("/doc.pdf").toString()).getOrThrow())!!

        val thumbnail = documents.thumbnailFor(documents.resolve(saved.documentPath!!)!!)
        assertThat(thumbnail.exists()).isTrue()
        assertThat(saved.imageUrl).isEqualTo(thumbnail.toURI().toString())
        val bitmap = BitmapFactory.decodeFile(thumbnail.path)
        assertThat(bitmap.width).isEqualTo(256)
        assertThat(bitmap.height).isEqualTo(256)
    }

    @Test
    fun `a shared file is stored under a content hash and titled from the file`() = runTest {
        val fixture = fixture("ssrn-6191618")
        val uri = share(fixture.file, "ssrn-6191618.pdf")

        val result = repo.saveDocument(uri, "ssrn-6191618.pdf")

        val saved = entries.findById(result.getOrThrow())!!
        assertThat(saved.guid).isEqualTo("perch:document:${fixture.sha256}")
        assertThat(saved.link).isNull()
        assertThat(saved.title).isEqualTo(fixture.title)
        assertThat(saved.isSaved).isTrue()
        assertThat(documents.resolve(saved.documentPath!!)!!.readBytes())
            .isEqualTo(fixture.file.readBytes())
        assertThat(feeds.findById(saved.feedId)!!.feedUrl).isEqualTo(FeedEntity.SAVED_LINKS_FEED_URL)
    }

    @Test
    fun `the same file shared twice is one row`() = runTest {
        val file = fixture("letter-margins").file

        val first = repo.saveDocument(share(file, "a.pdf"), "a.pdf").getOrThrow()
        val second = repo.saveDocument(share(file, "b.pdf"), "b.pdf").getOrThrow()

        assertThat(second).isEqualTo(first)
        assertThat(entries.countAll()).isEqualTo(1)
    }

    @Test
    fun `a shared file that is not a PDF fails as NotDocument`() = runTest {
        val uri = share(fixture("html-in-disguise").file, "html-in-disguise.pdf")

        val result = repo.saveDocument(uri, "html-in-disguise.pdf")

        assertThat(result.exceptionOrNull()).isInstanceOf(SaveLinkFailure.NotDocument::class.java)
        assertThat(entries.countAll()).isEqualTo(0)
        assertThat(documentFiles()).isEmpty()
    }

    @Test
    fun `a shared file with no title takes its display name`() = runTest {
        val uri = share(fixture("empty-title").file, "Reading_list notes.pdf")

        val saved = entries.findById(repo.saveDocument(uri, "Reading_list notes.pdf").getOrThrow())!!

        assertThat(saved.title).isEqualTo("Reading list notes")
    }

    /**
     * §0.8: the 40 MiB cap is applied *to the stream*. A share is whatever the sending app
     * hands over, so a copy that reads to the end before measuring would fill the disk with
     * a video someone shared by mistake before it said no.
     */
    @Test
    fun `a shared file over the cap is refused before it is read to the end`() = runTest {
        val cap = FeedFetcher.MAX_DOCUMENT_BYTES
        var read = 0L
        val uri = Uri.parse("content://test/huge.pdf")
        shared[uri] = {
            object : InputStream() {
                val header = "%PDF-1.7\n".toByteArray()
                override fun read(): Int {
                    if (read >= cap * 2) return -1
                    val b = if (read < header.size) header[read.toInt()].toInt() else 0
                    read++
                    return b
                }
            }
        }

        val result = repo.saveDocument(uri, "huge.pdf")

        assertThat(result.exceptionOrNull()).isInstanceOf(SaveLinkFailure.Unreachable::class.java)
        assertThat(result.exceptionOrNull()!!.message).contains("40 MiB")
        assertThat(read).isAtMost(cap + 64 * 1024)
        assertThat(documentFiles()).isEmpty()
    }

    private fun fixture(slug: String) = DocumentFixtures.manifest().first { it.slug == slug }

    private fun share(file: File, name: String): Uri {
        val uri = Uri.parse("content://test/$name")
        shared[uri] = { file.inputStream() }
        return uri
    }

    private fun documentFiles() = File(tmpDir.root, "documents").listFiles().orEmpty().toList()

    private fun article() = MockResponse()
        .setBody(
            """
            <html><head>
              <meta property="og:title" content="An Article">
              <meta property="article:published_time" content="2026-08-20T10:00:00Z">
            </head><body><article><p>${"Real prose, with commas and length. ".repeat(30)}</p></article></body></html>
            """.trimIndent(),
        )
        .addHeader("Content-Type", "text/html; charset=utf-8")
}
