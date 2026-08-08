package dev.mkiros.perch.data.parse

import java.time.Instant
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

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
        val items = root.childElementsNamed("item") + channel?.childElementsNamed("item").orEmpty()

        return ParsedFeed(
            title = plainText(channel?.childText("title"))
                ?: hostOf(siteUrl)
                ?: hostOf(requestUrl)
                ?: UNTITLED_FEED,
            siteUrl = siteUrl,
            updatedAt = updatedAt,
            entries = items.map { item -> entry(item, base, feedUpdatedAt = updatedAt) },
        )
    }

    private fun entry(item: Element, base: String?, feedUpdatedAt: Instant?): ParsedEntry {
        val title = plainText(item.childText("title")) ?: UNTITLED_ENTRY
        val link = resolveUrl(base, item.childText("link"))
        val publishedRaw = item.childText("dc:date", "dcterms:issued", "pubDate", "date")
        val publishedAt = dates.parse(publishedRaw)
        val contentHtml = item.childElement("content:encoded", "description")?.markup()
        // The entry's own page is what its relative URLs were written against.
        val imageBase = link ?: base

        return ParsedEntry(
            // rdf:about is a URI that names the item, not necessarily one we can open, so
            // it is kept verbatim as identity while `link` stays the thing the user taps.
            guid = item.attrNamed("about")
                ?: link
                ?: stableGuid(title, publishedRaw),
            title = title,
            link = link,
            author = personName(item.childText("dc:creator", "dc:publisher", "author")),
            publishedAt = publishedAt ?: feedUpdatedAt,
            publishedIsEstimated = publishedAt == null,
            contentHtml = contentHtml,
            imageUrl = LeadImage.fromItem(item, imageBase)
                ?: LeadImage.fromBody(contentHtml, imageBase),
        )
    }

    private companion object {
        const val UNTITLED_FEED = "(untitled feed)"
        const val UNTITLED_ENTRY = "(untitled)"
    }
}
