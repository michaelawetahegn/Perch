package dev.mkiros.perch.data.parse

import java.io.ByteArrayInputStream
import java.net.URI
import org.jsoup.Jsoup

/** A page someone fetched for us: what came back, and where it came back from. */
class FetchedPage(
    val bytes: ByteArray,
    val contentType: String?,
    /** The URL after redirects — what relative links resolve against. */
    val finalUrl: String,
)

/** The one network capability discovery needs. Null means "could not be fetched". */
fun interface PageFetcher {
    suspend fun fetch(url: String): FetchedPage?
}

/**
 * Turns whatever the user pasted into the URL we can actually poll, per SPEC.md §5.
 *
 * Three steps, cheapest first: if the address is already a feed we are done; if it is a
 * page that declares one, believe its declaration; only then start guessing the common
 * paths, and a guess counts only if what comes back genuinely parses. A site that soft-404s
 * every guess with a 200 HTML page (nccgroup.com does exactly this) must therefore come
 * back null rather than with an address that will fail forever afterwards.
 *
 * Whether the declared feed is reachable is deliberately *not* checked here — adding the
 * source fetches it anyway (T16), and that is where a broken declaration should be reported.
 */
class FeedDiscovery(
    private val fetcher: PageFetcher,
    private val parser: FeedParser = FeedParser(),
) {

    /**
     * The feed URL for [url], or null if the site does not appear to publish one.
     *
     * [page] is the response the caller already has for [url]; passing it keeps a
     * pasted feed address from being fetched twice.
     */
    suspend fun resolve(url: String, page: FetchedPage? = null): String? {
        val landing = page ?: fetcher.fetch(url) ?: return null
        return feedUrlOf(landing) ?: declaredFeedUrl(landing) ?: guessPaths(landing.finalUrl)
    }

    /** [page]'s own URL, if [page] is a feed. */
    private fun feedUrlOf(page: FetchedPage): String? =
        page.finalUrl.takeIf { parser.parse(page.bytes, page.contentType, it) is ParseResult.Success }

    /**
     * The best `<link rel="alternate">` the page declares. Attribute order, quoting and
     * line breaks inside the tag vary from site to site, so this goes through a real HTML
     * parser; `rel` may carry several tokens, and `type` may carry parameters.
     */
    private fun declaredFeedUrl(page: FetchedPage): String? {
        val document = runCatching {
            Jsoup.parse(ByteArrayInputStream(page.bytes), null, page.finalUrl)
        }.getOrNull() ?: return null

        val alternates = document.select("link[href]")
            .filter { link -> link.attr("rel").split(WHITESPACE).any { it.equals("alternate", true) } }

        return FEED_TYPES.firstNotNullOfOrNull { type ->
            alternates.firstOrNull { it.attr("type").substringBefore(';').trim().equals(type, true) }
                ?.absUrl("href")
                ?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * The conventional feed paths, tried against the host root — the pasted URL may be any
     * page on the site, and `/blog/2026/a-post/feed` is not where feeds live.
     */
    private suspend fun guessPaths(pageUrl: String): String? {
        val root = hostRoot(pageUrl) ?: return null
        return GUESSES.firstNotNullOfOrNull { guess ->
            fetcher.fetch(root + guess)?.let { feedUrlOf(it) }
        }
    }

    private fun hostRoot(url: String): String? = runCatching {
        val uri = URI(url)
        val host = uri.host ?: return null
        val scheme = uri.scheme ?: return null
        if (uri.port == -1) "$scheme://$host" else "$scheme://$host:${uri.port}"
    }.getOrNull()

    private companion object {
        /** SPEC.md §5's preference order: atom, then rss, then rdf. */
        val FEED_TYPES = listOf("application/atom+xml", "application/rss+xml", "application/rdf+xml")

        val GUESSES = listOf("/feed", "/rss.xml", "/atom.xml", "/index.xml", "/feed.xml", "/feeds/all.atom.xml")

        val WHITESPACE = Regex("""\s+""")
    }
}
