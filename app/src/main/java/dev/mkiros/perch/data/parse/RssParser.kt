package dev.mkiros.perch.data.parse

import org.jsoup.nodes.Document

/**
 * RSS 2.0 and 0.9x, per SPEC.md §5. RSS 1.0 is a different format on a different
 * namespace and belongs to `RdfParser`.
 *
 * Returns null when the document is not RSS at all, which is how `FeedParser` learns to
 * try the next parser. Anything that *is* RSS yields a feed, however damaged — a
 * truncated document contributes the entries it managed to close.
 */
class RssParser(private val dates: DateParser = DateParser()) {

    /** RSS 2.0's names for the rungs [ItemMapping] reads; `<guid>` is its item identity. */
    private val items = ItemMapping(
        dates = dates,
        dateElements = listOf("pubdate", "dc:date", "date", "published"),
        bodyElements = listOf("content:encoded", "description", "summary"),
        authorElements = listOf("dc:creator", "author", "dc:publisher"),
        identity = { item -> plainText(item.childText("guid")) },
    )

    fun parse(document: Document, requestUrl: String? = null): ParsedFeed? {
        val channel = document.childElement("rss")?.childElement("channel") ?: return null
        val siteUrl = resolveUrl(requestUrl, channel.childText("link"))
        val base = siteUrl ?: requestUrl
        val updatedAt = dates.parse(channel.childText("lastbuilddate", "pubdate", "dc:date"))

        return ParsedFeed(
            title = plainText(channel.childText("title"))
                ?: hostOf(siteUrl)
                ?: hostOf(requestUrl)
                ?: UNTITLED_FEED,
            siteUrl = siteUrl,
            updatedAt = updatedAt,
            entries = channel.getElementsByTag("item").map { item ->
                items.entry(item, base, feedUpdatedAt = updatedAt)
            },
        )
    }
}
