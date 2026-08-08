package dev.mkiros.perch.data.parse

import java.time.Instant
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Atom 1.0, per SPEC.md §5.
 *
 * Returns null when the document is not Atom, which is how `FeedParser` learns to try the
 * next parser. Anything that *is* Atom yields a feed, however damaged — a truncated
 * document contributes the entries it managed to close.
 */
class AtomParser(private val dates: DateParser = DateParser()) {

    fun parse(document: Document, requestUrl: String? = null): ParsedFeed? {
        val feed = document.childElement("feed") ?: return null
        val feedBase = resolveUrl(requestUrl, feed.attr("xml:base")) ?: requestUrl
        val siteUrl = alternateLink(feed, feedBase)
        val base = siteUrl ?: feedBase
        val updatedAt = dates.parse(feed.childText("updated", "published", "dc:date"))
        val feedAuthor = authorName(feed)

        return ParsedFeed(
            title = plainText(feed.childText("title", "subtitle"))
                ?: hostOf(siteUrl)
                ?: hostOf(requestUrl)
                ?: UNTITLED_FEED,
            siteUrl = siteUrl,
            updatedAt = updatedAt,
            entries = feed.getElementsByTag("entry").map { entry ->
                entry(entry, base, feedAuthor = feedAuthor, feedUpdatedAt = updatedAt)
            },
        )
    }

    private fun entry(
        entry: Element,
        base: String?,
        feedAuthor: String?,
        feedUpdatedAt: Instant?,
    ): ParsedEntry {
        // xml:base is rare but real (three corpus feeds use it) and it is the only thing
        // that makes their relative hrefs resolve to the right path.
        val entryBase = resolveUrl(base, entry.attr("xml:base")) ?: base
        val title = plainText(entry.childText("title")) ?: UNTITLED_ENTRY
        val link = alternateLink(entry, entryBase)
        // Atom's `published` is when the entry appeared; `updated` moves on every edit, so
        // it is only a fallback — sorting by it would keep resurfacing old posts.
        val publishedRaw = entry.childText("published", "updated", "dc:date", "issued", "modified")
        val publishedAt = dates.parse(publishedRaw)
        val contentHtml = content(entry)
        // Atom's own excerpt/article distinction, which is RSS's under different names:
        // `summary` is the blurb, `content` is the article (U10, PLAN-2 §0).
        val bodyIsExcerpt = contentHtml != null && entry.childElement("content") == null
        // The entry's own page is what its relative URLs were written against.
        val imageBase = link ?: entryBase

        return ParsedEntry(
            guid = plainText(entry.childText("id"))
                ?: link
                ?: stableGuid(title, publishedRaw),
            title = title,
            link = link,
            author = authorName(entry) ?: feedAuthor,
            publishedAt = publishedAt ?: feedUpdatedAt,
            publishedIsEstimated = publishedAt == null,
            contentHtml = contentHtml,
            imageUrl = LeadImage.fromEntry(entry, imageBase)
                ?: LeadImage.fromBody(contentHtml, imageBase),
            bodyIsExcerpt = bodyIsExcerpt,
        )
    }

    /**
     * The entry's human-readable link. `rel` defaults to `alternate` when absent, and an
     * entry commonly also carries `self`, `edit`, `replies` and `enclosure` links — taking
     * the first `<link>` blindly lands on a comments feed or an editing endpoint.
     * Among alternates, HTML wins over the machine-readable ones.
     */
    private fun alternateLink(element: Element, base: String?): String? {
        val alternates = element.childElements("link")
            .filter { it.attr("rel").ifBlank { ALTERNATE }.equals(ALTERNATE, ignoreCase = true) }
        val chosen = alternates.firstOrNull {
            val type = it.attr("type")
            type.isBlank() || type.equals("text/html", ignoreCase = true)
        } ?: alternates.firstOrNull()
        return resolveUrl(base, chosen?.attr("href"))
    }

    /**
     * `content` outranks `summary` (SPEC.md §5). Atom types its payload where RSS does
     * not: `xhtml` arrives as real elements inside a wrapper `<div>` that is scaffolding
     * rather than content, and `text` is literal — escaping it is what stops
     * `1 < 2` from being swallowed as a bogus tag downstream. A missing `type` is
     * *specified* as text, but feeds that omit it are overwhelmingly putting escaped HTML
     * there, so it is treated as markup.
     */
    private fun content(entry: Element): String? {
        val element = entry.childElement("content", "summary") ?: return null
        return when (element.attr("type").lowercase()) {
            "xhtml" -> (element.childElement("div") ?: element).markup()
            "text", "text/plain" -> element.wholeText().trim().takeIf { it.isNotEmpty() }
                ?.let(::escapeMarkup)
            else -> element.markup()
        }
    }

    /** The author's name, from `author/name`, `dc:creator`, or a bare mailbox. */
    private fun authorName(element: Element): String? {
        val author = element.childElement("author", "dc:creator") ?: return null
        if (author.tagName().equals("dc:creator", ignoreCase = true)) {
            return personName(author.wholeText())
        }
        return plainText(author.childText("name"))
            ?: personName(author.childText("email"))
            ?: personName(author.wholeText())
    }

    private fun escapeMarkup(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        const val ALTERNATE = "alternate"
        const val UNTITLED_FEED = "(untitled feed)"
        const val UNTITLED_ENTRY = "(untitled)"
    }
}
