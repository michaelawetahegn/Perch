package dev.mkiros.perch.data.repo

import dev.mkiros.perch.data.db.EntryDao
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.extract.ArticleExtractor
import dev.mkiros.perch.data.parse.HtmlSanitizer
import dev.mkiros.perch.data.parse.PageFetcher
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Goes and gets the article when the feed did not ship one (U10).
 *
 * The order of operations is the whole design. Fetch the entry's own page, extract, and
 * then — before writing anything — compare what came back with what the reader already
 * had. **An extraction only ever replaces a body it is longer than.** That single rule is
 * what makes it safe to run this automatically on open: the extractor is a heuristic, it
 * will be wrong on some page nobody has seen yet, and being wrong has to cost nothing.
 *
 * What it deliberately does *not* do is fetch during a refresh. Forty-two sources' worth of
 * article pages, once a day, is a different application from the one SPEC.md §6 describes.
 * Extraction happens when a reader opens one article, for that article.
 */
class ArticleTextRepository(
    private val entryDao: EntryDao,
    private val fetcher: PageFetcher,
    private val clock: Clock,
) {

    /**
     * Fetches [entryId]'s page and writes the article back, if there is more of it there.
     *
     * @return the updated row, or **null** when nothing changed — no link, no page, no
     *   article on the page, or an extraction no better than the body already stored. Null
     *   is the ordinary outcome for a feed that ships full text, not an error condition.
     */
    suspend fun loadFullText(entryId: Long): EntryEntity? {
        val entry = entryDao.findById(entryId) ?: return null
        val link = entry.link?.takeIf { it.isNotBlank() } ?: return null

        val page = fetcher.fetch(link) ?: return null
        val document = withContext(Dispatchers.Default) { parse(page.bytes, page.finalUrl) }
            ?: return null

        val extracted = ArticleExtractor.extract(document.outerHtml(), page.finalUrl)
        val safeHtml = HtmlSanitizer.sanitize(extracted, page.finalUrl)
        if (safeHtml == null || !isFullerThan(safeHtml, entry.contentHtml)) return null

        val updated = entry.copy(
            contentHtml = safeHtml,
            // The summary stays the feed's: it is the list row's snippet, and the article
            // screen already declines to print a standfirst that repeats the body's opening.
            fullTextAt = clock.millis(),
            // §0's last thumbnail rung, and the only place it is allowed to run — the page
            // is already open, so this costs nothing. The feed's own image outranks it.
            imageUrl = entry.imageUrl ?: document.ogImage(),
        )
        entryDao.update(updated)
        return updated
    }

    /** Compared as prose, not as markup: an extraction wraps its paragraphs more heavily. */
    private fun isFullerThan(candidate: String, existing: String?): Boolean =
        Jsoup.parse(candidate).text().length > Jsoup.parse(existing.orEmpty()).text().length

    /**
     * Bytes to a document, letting jsoup sniff the page's own `<meta charset>` — the
     * declaration in the markup beats the one in the header, and plenty of pages have only
     * the former.
     */
    private fun parse(bytes: ByteArray, baseUrl: String): Document? =
        runCatching { Jsoup.parse(bytes.inputStream(), null, baseUrl) }.getOrNull()

    private fun Document.ogImage(): String? =
        select("meta[property=og:image], meta[name=og:image], meta[name=twitter:image]")
            .firstNotNullOfOrNull { it.absUrl("content").takeIf { url -> url.isNotBlank() } }
}
