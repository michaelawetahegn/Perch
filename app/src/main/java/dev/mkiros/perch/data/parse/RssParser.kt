package dev.mkiros.perch.data.parse

import java.time.Instant
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * RSS 2.0 and 0.9x, per SPEC.md §5. RSS 1.0 is a different format on a different
 * namespace and belongs to `RdfParser`.
 *
 * Returns null when the document is not RSS at all, which is how `FeedParser` learns to
 * try the next parser. Anything that *is* RSS yields a feed, however damaged — a
 * truncated document contributes the entries it managed to close.
 */
class RssParser(private val dates: DateParser = DateParser()) {

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
                entry(item, base, feedUpdatedAt = updatedAt)
            },
        )
    }

    private fun entry(item: Element, base: String?, feedUpdatedAt: Instant?): ParsedEntry {
        val title = plainText(item.childText("title")) ?: UNTITLED_ENTRY
        val link = resolveUrl(base, item.childText("link"))
        val publishedRaw = item.childText("pubdate", "dc:date", "date", "published")
        val publishedAt = dates.parse(publishedRaw)
        val contentHtml = item.childElement("content:encoded", "description", "summary")?.markup()
        // The entry's own page is what its relative URLs were written against.
        val imageBase = link ?: base

        return ParsedEntry(
            guid = item.childText("guid")?.let { plainText(it) }
                ?: link
                ?: stableGuid(title, publishedRaw),
            title = title,
            link = link,
            author = personName(item.childText("dc:creator", "author", "dc:publisher")),
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
