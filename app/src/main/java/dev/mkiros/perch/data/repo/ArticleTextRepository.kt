package dev.mkiros.perch.data.repo

import dev.mkiros.perch.data.db.EntryDao
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.extract.ArticleExtractor
import dev.mkiros.perch.data.extract.PageContentExtractor
import dev.mkiros.perch.data.parse.LeadImage
import dev.mkiros.perch.data.parse.PageFetcher
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        val document = withContext(Dispatchers.Default) {
            PageContentExtractor.parse(page.bytes, page.finalUrl)
        }
            ?: return null

        val content = PageContentExtractor.extract(document, page.finalUrl)
        val safeHtml = content.bodyHtml?.takeIf { isFullerThan(it, entry.contentHtml) }

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
            ?: content.ogImageUrl
        if (safeHtml == null && imageUrl == entry.imageUrl) return null

        // Three columns, not the whole row (D03, #36). Seconds have passed since `entry`
        // was read, and the reader has been looking at the article for all of them: a
        // Read later, a Like, or a title a refresh corrected in that window all live in
        // columns this fetch does not own, and writing the row back would revert them.
        // The summary stays the feed's either way: it is the list row's snippet, and the
        // article screen already declines to print a standfirst that repeats the body's
        // opening.
        entryDao.setFullText(
            id = entry.id,
            contentHtml = safeHtml ?: entry.contentHtml,
            fullTextAt = if (safeHtml != null) clock.millis() else entry.fullTextAt,
            imageUrl = imageUrl,
        )
        // Re-read rather than reason about what the row now holds — whatever else landed
        // during the fetch is part of the answer, and the screen re-renders from it.
        val updated = entryDao.findById(entry.id) ?: return null
        // The recovered article is usually the first real body this entry has ever had, so
        // until this line the index only knew whatever stub the feed shipped (S08, #28).
        entryDao.index(updated)
        return updated.takeIf { safeHtml != null }
    }

    /** Compared as prose, not as markup: an extraction wraps its paragraphs more heavily. */
    private fun isFullerThan(candidate: String, existing: String?): Boolean =
        ArticleExtractor.textLength(candidate) > ArticleExtractor.textLength(existing)
}
