package dev.mkiros.perch.data.parse

import org.jsoup.nodes.Document

/**
 * RDF, i.e. RSS 1.0, per SPEC.md §5.
 *
 * Two things separate it from RSS 2.0 and drive everything below: `<item>` elements are
 * *siblings* of `<channel>` rather than its children, and an item's identity is the
 * `rdf:about` attribute — RSS 1.0 has no `<guid>`. The `rdf:` prefix is chosen by the
 * document rather than by the spec, so elements and attributes are matched by local name.
 *
 * Returns null when the document is not RDF, which is how [FeedParser] learns to try the
 * next parser. Anything that *is* RDF yields a feed, however damaged.
 */
class RdfParser(private val dates: DateParser = DateParser()) {

    /**
     * RSS 1.0's names for the rungs [ItemMapping] reads. `rdf:about` is a URI that names
     * the item, not necessarily one we can open, so it is kept verbatim as identity while
     * `link` stays the thing the user taps.
     */
    private val items = ItemMapping(
        dates = dates,
        dateElements = listOf("dc:date", "dcterms:issued", "pubDate", "date"),
        bodyElements = listOf("content:encoded", "description"),
        authorElements = listOf("dc:creator", "dc:publisher", "author"),
        identity = { item -> item.attrNamed("about") },
    )

    fun parse(document: Document, requestUrl: String? = null): ParsedFeed? {
        val root = document.childElementsNamed("RDF").firstOrNull() ?: return null
        val channel = root.childElementsNamed("channel").firstOrNull()
        val siteUrl = resolveUrl(requestUrl, channel?.childText("link"))
        val base = siteUrl ?: requestUrl
        val updatedAt = dates.parse(
            channel?.childText("dc:date", "dcterms:modified", "lastBuildDate", "pubDate"),
        )

        // Items belong beside the channel; feeds that nest them inside it are wrong but
        // common enough that dropping their entries would be the worse reading.
        val itemElements =
            root.childElementsNamed("item") + channel?.childElementsNamed("item").orEmpty()

        return ParsedFeed(
            title = plainText(channel?.childText("title"))
                ?: hostOf(siteUrl)
                ?: hostOf(requestUrl)
                ?: UNTITLED_FEED,
            siteUrl = siteUrl,
            updatedAt = updatedAt,
            entries = itemElements.map { item ->
                items.entry(item, base, feedUpdatedAt = updatedAt)
            },
        )
    }
}
