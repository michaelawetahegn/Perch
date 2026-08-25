package dev.mkiros.perch.data.archive

import dev.mkiros.perch.data.parse.DateParser
import dev.mkiros.perch.data.parse.FeedParser
import dev.mkiros.perch.data.parse.FetchedPage
import dev.mkiros.perch.data.parse.PageFetcher
import dev.mkiros.perch.data.parse.ParseResult
import dev.mkiros.perch.data.parse.attrNamed
import dev.mkiros.perch.data.parse.childElementsNamed
import dev.mkiros.perch.data.parse.childText
import dev.mkiros.perch.data.parse.localName
import dev.mkiros.perch.data.parse.parseFeedXml
import dev.mkiros.perch.data.parse.resolveUrl
import java.net.URI
import java.time.Instant
import java.util.zip.GZIPInputStream

/** One older post [ArchiveDiscovery] found, with whatever date it could attach to it. */
data class ArchivePost(val url: String, val lastmod: Instant? = null)

/**
 * Finds a site's older posts by published standards alone (PLAN-7 §0.2) — never by
 * knowing anything about the site. In preference order:
 *
 * 1. RFC 5005 `rel="prev-archive"`, followed from the feed the caller already has.
 * 2. `robots.txt`'s `Sitemap:` directive (RFC 9309 §2.2.3).
 * 3. The conventional `/sitemap.xml`, recursing into sitemap *index* files per the
 *    sitemaps.org protocol (bounded — see [MAX_SITEMAP_DEPTH] / [MAX_SITEMAPS]).
 *
 * A sitemap lists every URL a site cares to publish — tags, pages, the homepage — not
 * just posts. The one structural signal used here to tell a post from the rest is a
 * **dated URL path** (`/2026/07/27/…`, `/2026/07/…`): a permalink convention shared by
 * Jekyll, Hugo, WordPress and plenty of hand-rolled generators, not a fact about any one
 * of them. Nothing here checks a hostname, a path prefix, or a template fingerprint — the
 * grep gate at PLAN-7 §0.2 is what enforces that.
 *
 * Stores nothing and touches no database; Z02 decides what to do with what this returns.
 */
class ArchiveDiscovery(
    private val fetcher: PageFetcher,
    private val feedParser: FeedParser = FeedParser(),
    private val dates: DateParser = DateParser(),
) {

    /**
     * [siteUrl] is the site to look under. [feed] is the feed document the caller already
     * fetched, if any — passing it lets RFC 5005 be tried without a second fetch.
     */
    suspend fun discover(siteUrl: String, feed: FetchedPage? = null): List<ArchivePost> {
        archivedFeedPosts(feed)?.let { if (it.isNotEmpty()) return it.toPosts() }

        val root = hostRoot(siteUrl) ?: return emptyList()
        val sitemapUrls = sitemapsFromRobots(root).ifEmpty { listOf("$root/sitemap.xml") }

        val found = LinkedHashMap<String, Instant?>()
        val seen = mutableSetOf<String>()
        sitemapUrls.forEach { collectSitemap(it, found, seen, depth = 0) }
        return found.toPosts()
    }

    // -- RFC 5005: prev-archive from the feed itself -----------------------------------

    /**
     * Follows `rel="prev-archive"` as far as it goes, bounded by [MAX_ARCHIVE_HOPS].
     * Null means "this feed declares no archive relation at all" — the caller's cue to
     * fall through to sitemap discovery; an empty (non-null) map means the relation was
     * declared but led nowhere usable, which falls through too.
     */
    private suspend fun archivedFeedPosts(feed: FetchedPage?): LinkedHashMap<String, Instant?>? {
        val start = feed ?: return null
        var prevArchive: String? = prevArchiveLink(start) ?: return null

        val found = LinkedHashMap<String, Instant?>()
        var hops = 0
        while (prevArchive != null && hops < MAX_ARCHIVE_HOPS) {
            val next = fetcher.fetch(prevArchive) ?: break
            entriesOf(next).forEach { entry -> entry.link?.let { found.putIfAbsent(it, entry.publishedAt) } }
            prevArchive = prevArchiveLink(next)
            hops++
        }
        return found
    }

    private fun prevArchiveLink(page: FetchedPage): String? {
        val root = rootElement(page) ?: return null
        val link = root.childElementsNamed("link")
            .firstOrNull { it.attrNamed("rel")?.equals("prev-archive", ignoreCase = true) == true }
            ?: return null
        return resolveUrl(page.finalUrl, link.attrNamed("href"))
    }

    private fun entriesOf(page: FetchedPage) =
        (feedParser.parse(page.bytes, page.contentType, page.finalUrl) as? ParseResult.Success)
            ?.feed?.entries.orEmpty()

    // -- sitemaps.org: robots.txt, sitemap.xml, sitemap index recursion ----------------

    private suspend fun sitemapsFromRobots(root: String): List<String> {
        val page = fetcher.fetch("$root/robots.txt") ?: return emptyList()
        return String(page.bytes, Charsets.UTF_8).lineSequence()
            .mapNotNull { raw ->
                val line = raw.trim()
                if (!line.startsWith(SITEMAP_DIRECTIVE, ignoreCase = true)) return@mapNotNull null
                line.substringAfter(':').trim().takeIf { it.isNotEmpty() }
            }
            .toList()
    }

    /**
     * Fetches [url] as either a flat sitemap (collecting post-shaped URLs into [found]) or
     * a sitemap index (recursing into its children). [depth] and the size of [seen] are
     * both bounded — a hostile or merely enormous index must not turn one "add this site"
     * into an unbounded fetch storm.
     */
    private suspend fun collectSitemap(
        url: String,
        found: MutableMap<String, Instant?>,
        seen: MutableSet<String>,
        depth: Int,
    ) {
        if (depth > MAX_SITEMAP_DEPTH || seen.size >= MAX_SITEMAPS || url in seen) return
        seen += url

        val page = fetcher.fetch(url) ?: return
        val bytes = ungzip(page)
        val document = runCatching { parseFeedXml(String(bytes, Charsets.UTF_8), page.finalUrl) }.getOrNull()
        val root = document?.children()?.firstOrNull() ?: return

        when (root.localName().lowercase()) {
            "urlset" -> root.childElementsNamed("url").forEach { urlEl ->
                val loc = resolveUrl(page.finalUrl, urlEl.childText("loc")) ?: return@forEach
                if (isLikelyPost(loc)) found.putIfAbsent(loc, dates.parse(urlEl.childText("lastmod")))
            }
            "sitemapindex" -> root.childElementsNamed("sitemap").forEach { smEl ->
                val loc = resolveUrl(page.finalUrl, smEl.childText("loc")) ?: return@forEach
                collectSitemap(loc, found, seen, depth + 1)
            }
        }
    }

    /** Gzip is normal for a sitemap and part of the protocol — by suffix or, if a server omits it, by magic bytes. */
    private fun ungzip(page: FetchedPage): ByteArray {
        val looksGzipped = page.finalUrl.substringBefore('?').endsWith(".gz", ignoreCase = true) ||
            (page.bytes.size >= 2 && page.bytes[0] == GZIP_MAGIC_0 && page.bytes[1] == GZIP_MAGIC_1)
        if (!looksGzipped) return page.bytes
        return runCatching { GZIPInputStream(page.bytes.inputStream()).use { it.readBytes() } }
            .getOrDefault(page.bytes)
    }

    /**
     * The one structural signal this task uses to tell a post from a tag/page/index URL:
     * a dated path segment. Deliberately not a path *prefix* (`/blog/`, `/posts/`) — those
     * are a fact about one engine's choices, not a cross-site convention.
     */
    private fun isLikelyPost(url: String): Boolean {
        val path = runCatching { URI(url).path }.getOrNull() ?: return false
        return DATED_PATH.containsMatchIn(path)
    }

    // -- shared -------------------------------------------------------------------------

    private fun rootElement(page: FetchedPage) =
        runCatching { parseFeedXml(String(page.bytes, Charsets.UTF_8), page.finalUrl) }
            .getOrNull()?.children()?.firstOrNull()

    private fun hostRoot(url: String): String? = runCatching {
        val uri = URI(url)
        val host = uri.host ?: return null
        val scheme = uri.scheme ?: return null
        if (uri.port == -1) "$scheme://$host" else "$scheme://$host:${uri.port}"
    }.getOrNull()

    private fun Map<String, Instant?>.toPosts() = map { (url, lastmod) -> ArchivePost(url, lastmod) }

    private companion object {
        const val SITEMAP_DIRECTIVE = "Sitemap:"

        /**
         * An RFC 5005 archive can in principle chain forever; real blogs page it in
         * batches of dozens of entries, so twenty hops reaches thousands of posts while
         * still capping the worst case a buggy or adversarial feed can inflict.
         */
        const val MAX_ARCHIVE_HOPS = 20

        /**
         * Large sites nest sitemap indexes (an index of per-year indexes is common); three
         * levels covers that shape without following an index into itself indefinitely.
         */
        const val MAX_SITEMAP_DEPTH = 3

        /** Caps total sitemap fetches per discovery so one huge index can't fan out unboundedly. */
        const val MAX_SITEMAPS = 50

        const val GZIP_MAGIC_0 = 0x1f.toByte()
        const val GZIP_MAGIC_1 = 0x8b.toByte()

        /** `/2026/07/27/…` or `/2026/07/…` — a year-first dated permalink segment. */
        val DATED_PATH = Regex("""/(19|20)\d{2}/\d{1,2}(/\d{1,2})?(/|$)""")
    }
}
