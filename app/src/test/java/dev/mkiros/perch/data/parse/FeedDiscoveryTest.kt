package dev.mkiros.perch.data.parse

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * T11 — turning "what the user pasted" into "the URL we can actually poll".
 *
 * The three real homepages are the point of this test: each declares its feed with the
 * `<link>` attributes in a different order, one splits the tag across two lines, and one
 * points at a different host entirely. A regex-shaped implementation passes one of them
 * and quietly fails the others, which is how a paste-a-URL flow ends up "not working for
 * some sites". nccgroup.com is the negative case — it publishes no feed at all any more,
 * and every path guess answers 200 with the same HTML page, so the only correct answer is
 * null rather than a URL that will never parse.
 */
class FeedDiscoveryTest {

    @Test
    fun `discovers a feed declared with rel before href and a relative target`() = runTest {
        val fetcher = FakeFetcher()
        val discovery = FeedDiscovery(fetcher)

        val found = discovery.resolve(XANIA, homepage("xania-org", XANIA))

        assertThat(found).isEqualTo("https://xania.org/feed.atom")
    }

    @Test
    fun `discovers a feed declared with href before rel`() = runTest {
        val discovery = FeedDiscovery(FakeFetcher())

        val found = discovery.resolve(HILLEL, homepage("hillelwayne-com", HILLEL))

        assertThat(found).isEqualTo("https://www.hillelwayne.com/index.xml")
    }

    @Test
    fun `discovers a feed hosted on another domain and ignores non-feed alternates`() = runTest {
        val discovery = FeedDiscovery(FakeFetcher())

        val found = discovery.resolve(GWERN, homepage("gwern-net", GWERN))

        // The page also declares an `alternate` of type text/markdown, first in the file.
        assertThat(found).isEqualTo("https://gwern.substack.com/feed")
    }

    @Test
    fun `gives up on a site that declares no feed and soft-404s every path guess`() = runTest {
        val softFourOhFour = homepage("research-nccgroup-com", NCC)
        val fetcher = FakeFetcher(default = softFourOhFour)
        val discovery = FeedDiscovery(fetcher)

        val found = discovery.resolve(NCC, softFourOhFour)

        assertThat(found).isNull()
        assertThat(fetcher.requested).contains("https://www.nccgroup.com/feed")
    }

    @Test
    fun `returns a feed URL unchanged without going to the network`() = runTest {
        val fetcher = FakeFetcher()
        val discovery = FeedDiscovery(fetcher)

        val found = discovery.resolve(XANIA_FEED, page(ATOM, "application/atom+xml", XANIA_FEED))

        assertThat(found).isEqualTo(XANIA_FEED)
        assertThat(fetcher.requested).isEmpty()
    }

    @Test
    fun `prefers atom over rss over rdf`() = runTest {
        val html = """
            <html><head>
              <link rel="alternate" type="application/rdf+xml" href="/rdf">
              <link rel="alternate" type="application/rss+xml" href="/rss">
              <link rel="alternate" type="application/atom+xml" href="/atom">
            </head><body/></html>
        """.trimIndent()
        val discovery = FeedDiscovery(FakeFetcher())

        assertThat(discovery.resolve(SITE, page(html, "text/html", SITE)))
            .isEqualTo("https://example.com/atom")
    }

    @Test
    fun `falls back to rdf when it is the only declared feed`() = runTest {
        val html = """<html><head><link rel="alternate" type="application/rdf+xml"
                      href="feed.rdf"></head></html>"""
        val discovery = FeedDiscovery(FakeFetcher())

        assertThat(discovery.resolve("https://example.com/blog/", page(html, "text/html", "https://example.com/blog/")))
            .isEqualTo("https://example.com/blog/feed.rdf")
    }

    @Test
    fun `ignores an alternate link that is not a feed type`() = runTest {
        val html = """<html><head>
              <link rel="alternate" type="text/markdown" href="/index.md">
              <link rel="stylesheet" type="application/rss+xml" href="/not-a-feed">
            </head></html>"""
        val fetcher = FakeFetcher()
        val discovery = FeedDiscovery(fetcher)

        assertThat(discovery.resolve(SITE, page(html, "text/html", SITE))).isNull()
        // It fell through to path guessing rather than trusting either link.
        assertThat(fetcher.requested).isNotEmpty()
    }

    @Test
    fun `guesses the common paths in order and stops at the first that parses`() = runTest {
        val fetcher = FakeFetcher(
            pages = mapOf("https://example.com/atom.xml" to page(ATOM, "application/atom+xml", "https://example.com/atom.xml")),
            default = page(BLANK_HTML, "text/html", SITE),
        )
        val discovery = FeedDiscovery(fetcher)

        val found = discovery.resolve(SITE, page(BLANK_HTML, "text/html", SITE))

        assertThat(found).isEqualTo("https://example.com/atom.xml")
        assertThat(fetcher.requested).containsExactly(
            "https://example.com/feed",
            "https://example.com/rss.xml",
            "https://example.com/atom.xml",
        ).inOrder()
    }

    @Test
    fun `reports the URL a guess redirected to, not the URL it guessed`() = runTest {
        val fetcher = FakeFetcher(
            pages = mapOf("https://example.com/feed" to page(ATOM, "application/atom+xml", "https://example.com/feed.atom")),
        )
        val discovery = FeedDiscovery(fetcher)

        assertThat(discovery.resolve(SITE, page(BLANK_HTML, "text/html", SITE)))
            .isEqualTo("https://example.com/feed.atom")
    }

    @Test
    fun `guesses against the host root, not the pasted path`() = runTest {
        val deep = "https://example.com/blog/2026/some-post/"
        val fetcher = FakeFetcher(default = page(BLANK_HTML, "text/html", deep))
        val discovery = FeedDiscovery(fetcher)

        discovery.resolve(deep, page(BLANK_HTML, "text/html", deep))

        assertThat(fetcher.requested.first()).isEqualTo("https://example.com/feed")
    }

    @Test
    fun `fetches the pasted URL itself when the caller has not already`() = runTest {
        val fetcher = FakeFetcher(
            pages = mapOf(SITE to page(ATOM, "application/atom+xml", SITE)),
        )
        val discovery = FeedDiscovery(fetcher)

        assertThat(discovery.resolve(SITE)).isEqualTo(SITE)
        assertThat(fetcher.requested).containsExactly(SITE)
    }

    @Test
    fun `returns null when nothing can be fetched at all`() = runTest {
        val discovery = FeedDiscovery(FakeFetcher())

        assertThat(discovery.resolve(SITE)).isNull()
    }

    // -- fixtures -------------------------------------------------------------------

    private class FakeFetcher(
        private val pages: Map<String, FetchedPage> = emptyMap(),
        private val default: FetchedPage? = null,
    ) : PageFetcher {
        val requested = mutableListOf<String>()

        override suspend fun fetch(url: String): FetchedPage? {
            requested += url
            return pages[url] ?: default
        }
    }

    private companion object {
        const val XANIA = "https://xania.org/"
        const val XANIA_FEED = "https://xania.org/feed.atom"
        const val HILLEL = "https://www.hillelwayne.com/"
        const val GWERN = "https://gwern.net/"
        const val NCC = "https://www.nccgroup.com/research/"
        const val SITE = "https://example.com/"

        const val ATOM = """<?xml version="1.0"?>
            <feed xmlns="http://www.w3.org/2005/Atom"><title>A feed</title>
              <entry><title>An entry</title><updated>2026-01-01T00:00:00Z</updated></entry>
            </feed>"""

        const val BLANK_HTML = "<html><head><title>No feed here</title></head><body/></html>"

        fun page(body: String, contentType: String, finalUrl: String) =
            FetchedPage(body.toByteArray(), contentType, finalUrl)

        /** The homepage HTML T04 saved, served as the page the caller already fetched. */
        fun homepage(slug: String, finalUrl: String): FetchedPage {
            val file = File(repoRoot(), "fixtures/homepages/$slug.html")
            check(file.isFile) { "missing homepage fixture: $file" }
            return FetchedPage(file.readBytes(), "text/html", finalUrl)
        }

        private fun repoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            while (dir != null) {
                if (File(dir, "fixtures/homepages").isDirectory) return dir
                dir = dir.parentFile
            }
            error("fixtures/homepages not found")
        }
    }
}
