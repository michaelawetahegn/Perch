package dev.mkiros.perch.data.archive

import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.parse.FetchedPage
import dev.mkiros.perch.data.parse.PageFetcher
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Z01 — turning a site URL into candidate post URLs, by standards alone (PLAN-7 §0.2).
 *
 * Every fixture here is a *shape* (a flat sitemap, an index, a robots.txt redirect, a
 * gzip, an RFC 5005 chain, an empty site, a sitemap mixing posts with tags/pages) rather
 * than one real host — a heuristic that only fires for one site belongs nowhere near
 * this class.
 */
class ArchiveDiscoveryTest {

    @Test
    fun `reads a flat sitemap into posts with their lastmod dates`() = runTest {
        val fetcher = FakeFetcher(
            pages = mapOf(SITE + "sitemap.xml" to xml(FLAT_SITEMAP)),
        )
        val discovery = ArchiveDiscovery(fetcher)

        val posts = discovery.discover(SITE)

        assertThat(posts).containsExactly(
            ArchivePost("https://example.com/2026/07/27/a-post", Instant.parse("2026-07-27T00:00:00Z")),
            ArchivePost("https://example.com/2026/06/01/another-post", Instant.parse("2026-06-01T00:00:00Z")),
        )
    }

    @Test
    fun `recurses into a sitemap index and collects both children`() = runTest {
        val fetcher = FakeFetcher(
            pages = mapOf(
                SITE + "sitemap.xml" to xml(SITEMAP_INDEX),
                "https://example.com/sitemap-1.xml" to xml(oneUrlSitemap("https://example.com/2026/01/02/post-one")),
                "https://example.com/sitemap-2.xml" to xml(oneUrlSitemap("https://example.com/2025/11/03/post-two")),
            ),
        )
        val discovery = ArchiveDiscovery(fetcher)

        val posts = discovery.discover(SITE)

        assertThat(posts.map { it.url }).containsExactly(
            "https://example.com/2026/01/02/post-one",
            "https://example.com/2025/11/03/post-two",
        )
    }

    @Test
    fun `follows robots-txt to a sitemap at a non-default path`() = runTest {
        val fetcher = FakeFetcher(
            pages = mapOf(
                SITE + "robots.txt" to text("User-agent: *\nDisallow: /private/\nSitemap: https://example.com/custom/sitemap-posts.xml\n"),
                "https://example.com/custom/sitemap-posts.xml" to xml(oneUrlSitemap("https://example.com/2024/03/09/custom-post")),
            ),
        )
        val discovery = ArchiveDiscovery(fetcher)

        val posts = discovery.discover(SITE)

        assertThat(posts.map { it.url }).containsExactly("https://example.com/2024/03/09/custom-post")
        assertThat(fetcher.requested).doesNotContain(SITE + "sitemap.xml")
    }

    @Test
    fun `ungzips a gzipped sitemap`() = runTest {
        val gzUrl = "https://example.com/sitemap.xml.gz"
        val fetcher = FakeFetcher(
            pages = mapOf(
                SITE + "robots.txt" to text("Sitemap: $gzUrl\n"),
                gzUrl to FetchedPage(
                    gzip(oneUrlSitemap("https://example.com/2023/05/17/gz-post")),
                    "application/gzip",
                    gzUrl,
                ),
            ),
        )
        val discovery = ArchiveDiscovery(fetcher)

        val posts = discovery.discover(SITE)

        assertThat(posts.map { it.url }).containsExactly("https://example.com/2023/05/17/gz-post")
    }

    @Test
    fun `returns nothing rather than throwing for a site with no archive at all`() = runTest {
        val discovery = ArchiveDiscovery(FakeFetcher())

        val posts = discovery.discover(SITE)

        assertThat(posts).isEmpty()
    }

    @Test
    fun `does not mistake a tag or page URL for a post`() = runTest {
        val fetcher = FakeFetcher(
            pages = mapOf(SITE + "sitemap.xml" to xml(MIXED_SITEMAP)),
        )
        val discovery = ArchiveDiscovery(fetcher)

        val posts = discovery.discover(SITE)

        assertThat(posts.map { it.url }).containsExactly("https://example.com/2026/02/14/real-post")
    }

    @Test
    fun `follows RFC 5005 prev-archive from the feed and never touches robots or sitemaps`() = runTest {
        val page2 = "https://example.com/feed?page=2"
        val page3 = "https://example.com/feed?page=3"
        val fetcher = FakeFetcher(
            pages = mapOf(
                page2 to xml(archivedFeedPage(entryUrl = "https://example.com/2026/01/01/older", nextArchive = page3), finalUrl = page2),
                page3 to xml(archivedFeedPage(entryUrl = "https://example.com/2025/12/01/oldest", nextArchive = null), finalUrl = page3),
            ),
        )
        val discovery = ArchiveDiscovery(fetcher)
        val feed = xml(feedWithPrevArchive(page2))

        val posts = discovery.discover(SITE, feed = feed)

        assertThat(posts.map { it.url }).containsExactly(
            "https://example.com/2026/01/01/older",
            "https://example.com/2025/12/01/oldest",
        )
        assertThat(fetcher.requested).containsExactly(page2, page3)
    }

    // -- fixtures -------------------------------------------------------------------

    private class FakeFetcher(private val pages: Map<String, FetchedPage> = emptyMap()) : PageFetcher {
        val requested = mutableListOf<String>()

        override suspend fun fetch(url: String): FetchedPage? {
            requested += url
            return pages[url]
        }
    }

    private companion object {
        const val SITE = "https://example.com/"

        fun text(body: String, finalUrl: String = "") = FetchedPage(body.toByteArray(), "text/plain", finalUrl)

        fun xml(body: String, finalUrl: String = SITE) = FetchedPage(body.toByteArray(), "application/xml", finalUrl)

        fun gzip(body: String): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(body.toByteArray()) }
            return out.toByteArray()
        }

        val FLAT_SITEMAP = """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              <url><loc>https://example.com/2026/07/27/a-post</loc><lastmod>2026-07-27</lastmod></url>
              <url><loc>https://example.com/2026/06/01/another-post</loc><lastmod>2026-06-01</lastmod></url>
            </urlset>
        """.trimIndent()

        val SITEMAP_INDEX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              <sitemap><loc>https://example.com/sitemap-1.xml</loc></sitemap>
              <sitemap><loc>https://example.com/sitemap-2.xml</loc></sitemap>
            </sitemapindex>
        """.trimIndent()

        val MIXED_SITEMAP = """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              <url><loc>https://example.com/2026/02/14/real-post</loc><lastmod>2026-02-14</lastmod></url>
              <url><loc>https://example.com/tags/kotlin/</loc></url>
              <url><loc>https://example.com/about/</loc></url>
              <url><loc>https://example.com/</loc></url>
            </urlset>
        """.trimIndent()

        fun oneUrlSitemap(url: String) = """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              <url><loc>$url</loc></url>
            </urlset>
        """.trimIndent()

        fun feedWithPrevArchive(archiveUrl: String) = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom" xmlns:fh="http://purl.org/syndication/history/1.0">
              <title>A feed</title>
              <link rel="prev-archive" href="$archiveUrl"/>
              <entry><title>Newest</title><id>newest</id><updated>2026-08-01T00:00:00Z</updated></entry>
            </feed>
        """.trimIndent()

        fun archivedFeedPage(entryUrl: String, nextArchive: String?) = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom" xmlns:fh="http://purl.org/syndication/history/1.0">
              <title>A feed</title>
              ${nextArchive?.let { """<link rel="prev-archive" href="$it"/>""" }.orEmpty()}
              <entry>
                <title>Older</title>
                <id>$entryUrl</id>
                <link href="$entryUrl"/>
                <updated>2025-01-01T00:00:00Z</updated>
              </entry>
            </feed>
        """.trimIndent()
    }
}
