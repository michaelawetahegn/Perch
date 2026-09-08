package dev.mkiros.perch.support

import dev.mkiros.perch.data.parse.FetchedPage
import dev.mkiros.perch.data.parse.PageFetcher
import kotlinx.coroutines.CancellationException

/**
 * The network, as a map: a URL either has a page in [pages], falls back to [default], or
 * comes back null the way an unreachable one does. Every URL asked for lands in
 * [requested], because "which addresses did it try, and in what order" is the claim half
 * the discovery tests make.
 *
 * A fetcher that answers nothing at all is `PageFetcher { null }` — [PageFetcher] is a
 * `fun interface` and needs no class of its own.
 *
 * @param stampFinalUrl for fixtures built before they know their own address: a page
 *  stored under a URL with an empty `finalUrl` is handed back stamped with the URL it was
 *  fetched from, so `guid = final URL` holds downstream.
 */
class MapPageFetcher(
    pages: Map<String, FetchedPage> = emptyMap(),
    private val default: FetchedPage? = null,
    private val stampFinalUrl: Boolean = false,
) : PageFetcher {

    val pages: MutableMap<String, FetchedPage> = pages.toMutableMap()
    val requested = mutableListOf<String>()

    /** The one URL whose fetch raises a cancellation instead of answering. */
    var cancelOn: String? = null

    override suspend fun fetch(url: String): FetchedPage? {
        requested += url
        if (url == cancelOn) throw CancellationException("the fetch was cancelled")
        val page = pages[url] ?: return default
        return if (stampFinalUrl && page.finalUrl.isEmpty()) {
            FetchedPage(page.bytes, page.contentType, url)
        } else {
            page
        }
    }
}
