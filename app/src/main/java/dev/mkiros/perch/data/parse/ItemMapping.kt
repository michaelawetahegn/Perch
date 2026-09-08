package dev.mkiros.perch.data.parse

import java.time.Instant
import org.jsoup.nodes.Element

/**
 * The one mapping from an RSS-family `<item>` to a [ParsedEntry], shared by [RssParser]
 * and [RdfParser]. Every rung below — the title fallback, URL resolution against the
 * entry's own page, the date, the body, the excerpt rule, the lead image — is the same
 * reading of the same item in both formats; SPEC.md §5 describes it once and so does this.
 *
 * The two formats disagree only about *names*: which elements carry a date, a body and an
 * author, and where an item's identity lives — a `<guid>` child in RSS 2.0, an `rdf:about`
 * attribute in RSS 1.0, which has no `<guid>` at all. Those are the constructor arguments,
 * so a format is a list of names rather than a copy of the function.
 *
 * Names are given most-preferred-first: [childText] and [childElement] take them in the
 * order supplied, not in document order.
 */
internal class ItemMapping(
    private val dates: DateParser,
    private val dateElements: List<String>,
    private val bodyElements: List<String>,
    private val authorElements: List<String>,
    private val identity: (Element) -> String?,
) {

    fun entry(item: Element, base: String?, feedUpdatedAt: Instant?): ParsedEntry {
        val title = plainText(item.childText("title")) ?: UNTITLED_ENTRY
        val link = resolveUrl(base, item.childText("link"))
        val publishedRaw = item.childText(*dateElements.toTypedArray())
        val publishedAt = dates.parse(publishedRaw)
        val body = item.childElement(*bodyElements.toTypedArray())
        val contentHtml = body?.markup()
        // Both formats draw the same line: `content:encoded` is the article and anything
        // else is the blurb (U10, PLAN-2 §0). §0's gpuopen shape is why — a `<description>`
        // with no `<content:encoded>` beside it is a teaser far more often than it is a
        // short post, and nothing downstream can tell.
        val bodyIsExcerpt = contentHtml != null &&
            !body.tagName().equals("content:encoded", ignoreCase = true)
        // The entry's own page is what its relative URLs were written against.
        val imageBase = link ?: base

        return ParsedEntry(
            guid = identity(item)
                ?: link
                ?: stableGuid(title, publishedRaw),
            title = title,
            link = link,
            author = personName(item.childText(*authorElements.toTypedArray())),
            publishedAt = publishedAt ?: feedUpdatedAt,
            publishedIsEstimated = publishedAt == null,
            contentHtml = contentHtml,
            imageUrl = LeadImage.fromItem(item, imageBase)
                ?: LeadImage.fromBody(contentHtml, imageBase),
            bodyIsExcerpt = bodyIsExcerpt,
        )
    }
}
