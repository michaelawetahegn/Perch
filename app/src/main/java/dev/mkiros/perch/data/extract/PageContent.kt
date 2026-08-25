package dev.mkiros.perch.data.extract

import dev.mkiros.perch.data.parse.HtmlSanitizer
import org.jsoup.nodes.Document

/**
 * What a fetched page's own markup says about the article on it — read once, by
 * [PageContentExtractor], and shared by every caller that fetches a *page* rather than an
 * entry (PLAN-6 §0, Y03): [dev.mkiros.perch.data.repo.ArticleTextRepository] recovering a
 * body a feed did not ship, and [dev.mkiros.perch.data.repo.SavedLinkRepository] saving a
 * link that was never subscribed to. Neither writes a row here — that decision (whether an
 * extraction beats the body already stored, which reader flags to set) is the caller's, and
 * differs between the two; this is only the value both read the same way.
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

    private fun Document.ogImage(): String? =
        select("meta[property=og:image], meta[name=og:image], meta[name=twitter:image]")
            .firstNotNullOfOrNull { it.absUrl("content").takeIf { url -> url.isNotBlank() } }
}
