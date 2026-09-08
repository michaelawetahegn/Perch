package dev.mkiros.perch.data.extract

import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.parse.HtmlSanitizer
import dev.mkiros.perch.data.parse.LeadImage
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * What a fetched page's own markup says about the article on it — read once, by
 * [PageContentExtractor], and shared by every caller that fetches a *page* rather than an
 * entry (PLAN-6 §0, Y03): [dev.mkiros.perch.data.repo.ArticleTextRepository] recovering a
 * body a feed did not ship, [dev.mkiros.perch.data.repo.SavedLinkRepository] saving a link
 * that was never subscribed to, and [dev.mkiros.perch.data.repo.BackfillRepository] walking
 * an archive. None of them writes a row here — that decision (whether an extraction beats
 * the body already stored, which reader flags to set) is the caller's, and differs between
 * them; this is only the value they all read the same way.
 *
 * @param metadata title and published date, by [PageMetadataExtractor] (Y01).
 * @param bodyHtml the sanitized article body, or null if the page has none —
 *   [ArticleExtractor]'s ordinary "no article here" answer, not an error.
 * @param ogImageUrl the page's own social-card image, the weakest rung in every caller's
 *   image chain (`data-repo.ArticleTextRepository`, PLAN-2 §0) — never the whole chain,
 *   since a caller may have a body of its own, or an entry's existing thumbnail, that
 *   outranks it.
 */
data class PageContent(
    val metadata: PageMetadata,
    val bodyHtml: String?,
    val ogImageUrl: String?,
)

/**
 * Extracts [PageContent] from a page's own `<head>` and `<body>`.
 *
 * `Document`-in, value-out — no network, no DB, same shape as [ArticleExtractor] and
 * [PageMetadataExtractor]. **Must run against the unlowered document** — see
 * [PageMetadataExtractor]'s own warning, which applies here too since both read the same
 * `Document`.
 */
object PageContentExtractor {

    fun extract(document: Document, baseUrl: String): PageContent {
        val extracted = ArticleExtractor.extract(document.outerHtml(), baseUrl)
        val bodyHtml = HtmlSanitizer.sanitize(extracted, baseUrl)
        return PageContent(
            metadata = PageMetadataExtractor.extract(document, baseUrl),
            bodyHtml = bodyHtml,
            ogImageUrl = document.ogImage(),
        )
    }

    /**
     * Bytes to a document, letting jsoup sniff the page's own `<meta charset>` — the
     * declaration in the markup beats the one in the header, and plenty of pages have only
     * the former. Null is "these bytes are not a page we can read", the ordinary answer for
     * a binary body, not an error.
     *
     * Every caller that fetches a *page* comes through here (D16), so jsoup stays inside
     * `data/extract` and the repositories hold no intermediate they did not ask for.
     */
    fun parse(bytes: ByteArray, baseUrl: String): Document? =
        runCatching { Jsoup.parse(bytes.inputStream(), null, baseUrl) }.getOrNull()

    private fun Document.ogImage(): String? =
        select("meta[property=og:image], meta[name=og:image], meta[name=twitter:image]")
            .firstNotNullOfOrNull { it.absUrl("content").takeIf { url -> url.isNotBlank() } }
}

/**
 * The row a fetched page becomes, for the two callers that store one they were never sent
 * as a feed entry: a pasted link and a backfilled archive post (D16).
 *
 * The six rungs are the same for both, and were written out twice before this existed. What
 * genuinely differs between them is the date — a saved link falls back to "now", a
 * backfilled post to the sitemap's `<lastmod>` and then to [java.time.Instant.EPOCH] — and
 * the reader flags, so both arrive as parameters. Nothing here writes: the caller owns the
 * upsert and whatever it does afterwards.
 *
 * @param finalUrl the URL after redirects, which is both the guid and the link: a page
 *   stored twice under two addresses that redirect to one place is the duplicate this
 *   avoids.
 */
fun PageContent.toEntry(
    feedId: Long,
    finalUrl: String,
    publishedAt: Long,
    publishedIsEstimated: Boolean,
    fetchedAt: Long,
    isSaved: Boolean = false,
    savedAt: Long? = null,
): EntryEntity = EntryEntity(
    feedId = feedId,
    guid = finalUrl,
    // A page with no title at all (no `<head>`, U01's corpus has several) still stores —
    // the reader can still open it by its address.
    title = metadata.title ?: finalUrl,
    link = finalUrl,
    author = null,
    publishedAt = publishedAt,
    publishedIsEstimated = publishedIsEstimated,
    summary = HtmlSanitizer.summarize(bodyHtml),
    contentHtml = bodyHtml,
    imageUrl = bodyHtml?.let { LeadImage.fromBody(it, finalUrl) } ?: ogImageUrl,
    readAt = null,
    isSaved = isSaved,
    savedAt = savedAt,
    fetchedAt = fetchedAt,
)
