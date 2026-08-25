package dev.mkiros.perch.data.repo

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.EntryDao
import dev.mkiros.perch.data.db.FeedDao
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.net.FeedFetcher
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
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

    private lateinit var db: PerchDatabase
    private lateinit var feeds: FeedDao
    private lateinit var entries: EntryDao
    private lateinit var server: MockWebServer
    private lateinit var repo: SavedLinkRepository

    private val now = Instant.parse("2026-08-25T12:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        db = PerchDatabase.inMemory(ApplicationProvider.getApplicationContext())
        feeds = db.feedDao()
        entries = db.entryDao()
        server = MockWebServer()
        server.start()
        repo = SavedLinkRepository(
            feedDao = feeds,
            entryDao = entries,
            fetcher = FeedFetcher(
                OkHttpClient.Builder().readTimeout(500, TimeUnit.MILLISECONDS).build(),
            ),
            clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
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
