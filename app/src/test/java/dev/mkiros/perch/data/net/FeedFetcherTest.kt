package dev.mkiros.perch.data.net

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The contract of SPEC.md §6: conditional GET is mandatory, a 304 costs nothing, and every
 * way a fetch can go wrong is a per-source message rather than an exception.
 */
class FeedFetcherTest {

    private lateinit var server: MockWebServer
    private lateinit var bodyReads: BodyReadCounter
    private lateinit var fetcher: FeedFetcher

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        bodyReads = BodyReadCounter()
        fetcher = FeedFetcher(
            OkHttpClient.Builder()
                .addNetworkInterceptor(bodyReads)
                .readTimeout(250, TimeUnit.MILLISECONDS)
                .build()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun url(path: String = "/feed.xml") = server.url(path).toString()

    @Test
    fun `a 200 hands back the body and the validators to store`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("<rss/>")
                .addHeader("ETag", "\"v1\"")
                .addHeader("Last-Modified", "Wed, 05 Aug 2026 10:00:00 GMT")
                .addHeader("Content-Type", "application/rss+xml; charset=utf-8")
        )

        val result = fetcher.fetch(url(), etag = null, lastModified = null)

        assertThat(result).isInstanceOf(FetchResult.Success::class.java)
        val success = result as FetchResult.Success
        assertThat(String(success.bytes)).isEqualTo("<rss/>")
        assertThat(success.etag).isEqualTo("\"v1\"")
        assertThat(success.lastModified).isEqualTo("Wed, 05 Aug 2026 10:00:00 GMT")
        assertThat(success.contentType).contains("application/rss+xml")
        assertThat(success.finalUrl).isEqualTo(url())
    }

    @Test
    fun `a first fetch sends no conditional headers`() = runTest {
        server.enqueue(MockResponse().setBody("<rss/>"))

        fetcher.fetch(url(), etag = null, lastModified = null)

        val request = server.takeRequest()
        assertThat(request.getHeader("If-None-Match")).isNull()
        assertThat(request.getHeader("If-Modified-Since")).isNull()
    }

    @Test
    fun `stored validators are sent back on the following fetch`() = runTest {
        server.enqueue(MockResponse().setBody("<rss/>").addHeader("ETag", "\"v1\""))
        server.enqueue(MockResponse().setResponseCode(304))

        val first = fetcher.fetch(url(), etag = null, lastModified = null) as FetchResult.Success
        server.takeRequest()
        fetcher.fetch(url(), etag = first.etag, lastModified = "Wed, 05 Aug 2026 10:00:00 GMT")

        val conditional = server.takeRequest()
        assertThat(conditional.getHeader("If-None-Match")).isEqualTo("\"v1\"")
        assertThat(conditional.getHeader("If-Modified-Since"))
            .isEqualTo("Wed, 05 Aug 2026 10:00:00 GMT")
    }

    @Test
    fun `a 304 is not modified`() = runTest {
        server.enqueue(MockResponse().setResponseCode(304))

        val result = fetcher.fetch(url(), etag = "\"v1\"", lastModified = null)

        assertThat(result).isEqualTo(FetchResult.NotModified)
    }

    @Test
    fun `a 304 reads no body at all`() = runTest {
        server.enqueue(MockResponse().setResponseCode(304))

        fetcher.fetch(url(), etag = "\"v1\"", lastModified = null)

        assertThat(bodyReads.reads).isEqualTo(0)
    }

    @Test
    fun `a redirect is followed and the final url is what gets recorded`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(301).addHeader("Location", "/moved/feed.xml")
        )
        server.enqueue(MockResponse().setBody("<rss/>"))

        val result = fetcher.fetch(url(), etag = null, lastModified = null)

        assertThat((result as FetchResult.Success).finalUrl).isEqualTo(url("/moved/feed.xml"))
    }

    @Test
    fun `a 404 fails with a message naming the status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = fetcher.fetch(url(), etag = null, lastModified = null)

        assertThat((result as FetchResult.Failure).message).contains("404")
    }

    @Test
    fun `a 500 fails with a message naming the status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = fetcher.fetch(url(), etag = null, lastModified = null)

        assertThat((result as FetchResult.Failure).message).contains("500")
    }

    @Test
    fun `a server that never answers fails with a timeout message`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val result = fetcher.fetch(url(), etag = null, lastModified = null)

        assertThat(result).isInstanceOf(FetchResult.Failure::class.java)
        assertThat((result as FetchResult.Failure).message.lowercase()).contains("timed out")
    }

    @Test
    fun `a body over the eight mebibyte cap fails instead of being kept`() = runTest {
        val oversized = Buffer().write(ByteArray(9 * 1024 * 1024))
        server.enqueue(MockResponse().setBody(oversized))

        val result = fetcher.fetch(url(), etag = null, lastModified = null)

        assertThat(result).isInstanceOf(FetchResult.Failure::class.java)
        assertThat((result as FetchResult.Failure).message.lowercase()).contains("too large")
    }

    @Test
    fun `an oversized body still fails when no content-length is declared`() = runTest {
        val oversized = Buffer().write(ByteArray(9 * 1024 * 1024))
        server.enqueue(MockResponse().setChunkedBody(oversized, 64 * 1024))

        val result = fetcher.fetch(url(), etag = null, lastModified = null)

        assertThat(result).isInstanceOf(FetchResult.Failure::class.java)
        assertThat((result as FetchResult.Failure).message.lowercase()).contains("too large")
    }

    @Test
    fun `an oversized body announced by content-length is refused before downloading`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setBody("<rss/>")
                    .setHeader("Content-Length", (20L * 1024 * 1024).toString())
                    .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
            )

            val result = fetcher.fetch(url(), etag = null, lastModified = null)

            assertThat(result).isInstanceOf(FetchResult.Failure::class.java)
            assertThat(bodyReads.reads).isEqualTo(0)
        }

    @Test
    fun `an unusable address fails rather than throwing`() = runTest {
        val result = fetcher.fetch("not a url", etag = null, lastModified = null)

        assertThat(result).isInstanceOf(FetchResult.Failure::class.java)
        assertThat((result as FetchResult.Failure).message).isNotEmpty()
    }

    @Test
    fun `it also serves as the page fetcher discovery needs`() = runTest {
        server.enqueue(
            MockResponse().setBody("<html/>").addHeader("Content-Type", "text/html")
        )

        val page = fetcher.fetch(url("/"))

        assertThat(page).isNotNull()
        assertThat(String(page!!.bytes)).isEqualTo("<html/>")
        assertThat(page.contentType).contains("text/html")
        assertThat(page.finalUrl).isEqualTo(url("/"))
    }

    @Test
    fun `a failed page fetch is null rather than an exception`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertThat(fetcher.fetch(url("/"))).isNull()
    }

    @Test
    fun `the shared client identifies itself as Perch`() = runTest {
        server.enqueue(MockResponse().setBody("<rss/>"))

        FeedFetcher(PerchHttp.client(cacheDir = null))
            .fetch(url(), etag = null, lastModified = null)

        assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo(PerchHttp.USER_AGENT)
    }

    /** Counts bytes pulled off the wire, so "a 304 costs nothing" can be asserted literally. */
    private class BodyReadCounter : Interceptor {
        var reads = 0L
            private set

        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            val body = response.body ?: return response
            val counted = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val n = super.read(sink, byteCount)
                    if (n > 0) reads += n
                    return n
                }
            }
            return response.newBuilder().body(counted.wrap(body)).build()
        }

        private fun Source.wrap(original: ResponseBody): ResponseBody =
            buffer().asResponseBody(original.contentType(), original.contentLength())
    }
}
