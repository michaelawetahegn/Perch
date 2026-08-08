package dev.mkiros.perch.data.repo

import dev.mkiros.perch.data.db.EntryDao
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.extract.ArticleExtractor
import dev.mkiros.perch.data.parse.HtmlSanitizer
import dev.mkiros.perch.data.parse.LeadImage
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
     * @return the updated row when the **body** changed, or **null** when it did not — no
     *   link, no page, no article on the page, or an extraction no better than the body
     *   already stored. Null is the ordinary outcome for a feed that ships full text, not
     *   an error condition. A null return does not mean nothing was written: a page that
     *   loses the body argument can still hand over a thumbnail (see below).
     */
    suspend fun loadFullText(entryId: Long): EntryEntity? {
        val entry = entryDao.findById(entryId) ?: return null
        val link = entry.link?.takeIf { it.isNotBlank() } ?: return null

        val page = fetcher.fetch(link) ?: return null
        val document = withContext(Dispatchers.Default) { parse(page.bytes, page.finalUrl) }
            ?: return null

        val extracted = ArticleExtractor.extract(document.outerHtml(), page.finalUrl)
        val safeHtml = HtmlSanitizer.sanitize(extracted, page.finalUrl)
            ?.takeIf { isFullerThan(it, entry.contentHtml) }

        // §0's last two thumbnail rungs, and the only place they are allowed to run — the
        // page is already open, so this costs nothing. Decided *independently* of the body,
        // because the two questions are independent: whether the page has a better article
        // than the feed says nothing about whether it has a picture, and a reader whose
        // extraction lost by fifty characters is owed the thumbnail all the same. The feed's
        // own image still outranks both.
        //
        // The body rung is asked of the body the reader ends up with. A recovered article
        // brings its own pictures with it, and the author's first diagram beats the
        // site-wide social card `og:image` so often is.
        val imageUrl = entry.imageUrl
            ?: safeHtml?.let { LeadImage.fromBody(it, page.finalUrl) }
            ?: document.ogImage()
        if (safeHtml == null && imageUrl == entry.imageUrl) return null

        val updated = entry.copy(
            contentHtml = safeHtml ?: entry.contentHtml,
            // The summary stays the feed's: it is the list row's snippet, and the article
            // screen already declines to print a standfirst that repeats the body's opening.
            fullTextAt = if (safeHtml != null) clock.millis() else entry.fullTextAt,
            imageUrl = imageUrl,
        )
        entryDao.update(updated)
        return updated.takeIf { safeHtml != null }
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
