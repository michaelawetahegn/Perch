package dev.mkiros.perch.data.repo

import dev.mkiros.perch.data.db.EntryDao
import dev.mkiros.perch.data.db.FeedDao
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.extract.PageContentExtractor
import dev.mkiros.perch.data.net.FeedFetcher
import dev.mkiros.perch.data.net.FetchResult
import dev.mkiros.perch.data.parse.FeedParser
import dev.mkiros.perch.data.parse.HtmlSanitizer
import dev.mkiros.perch.data.parse.LeadImage
import dev.mkiros.perch.data.parse.ParseResult
import dev.mkiros.perch.ui.source.normalizePastedUrl
import java.time.Clock
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Why a pasted link did not become a saved entry (PLAN-6 §0.4) — every reason
 * [SavedLinkRepository.saveLink] can fail is a value in [Result.failure], copying
 * [SourceResolution]'s shape rather than an exception the caller has to catch.
 */
sealed class SaveLinkFailure(message: String) : Exception(message) {

    /** Reachable, and it parses as a feed — the other feature, not an error (§0.4). */
    class IsFeed(val url: String) : SaveLinkFailure("That address is a feed, not an article.")

    /** Could not be fetched, or was too large or not an article; already phrased for the user. */
    class Unreachable(message: String) : SaveLinkFailure(message)
}

/**
 * A link the reader pastes, saved without ever subscribing to its site (PLAN-6 §0.3/§0.4,
 * issue #23).
 *
 * Everything [ArticleTextRepository] does for an entry it already has, done once for a page
 * it does not: fetch, extract title/date/body/image with [PageContentExtractor] — the same
 * value object [ArticleTextRepository] reads, so a page is read the same way whether Perch
 * already knew about it or a reader just pasted it — then file the result on the synthetic
 * `perch:saved-links` feed (§0.3), which satisfies the same foreign key and the same joins
 * every other entry does.
 *
 * Identity is `(feedId, guid)`, same as a fed entry, with the **final URL as the guid**
 * (§0.3) — pasting the same link twice lands on the same row through [EntryDao.upsertAll]
 * rather than duplicating it.
 */
class SavedLinkRepository(
    private val feedDao: FeedDao,
    private val entryDao: EntryDao,
    private val fetcher: FeedFetcher,
    private val clock: Clock,
    private val parser: FeedParser = FeedParser(),
) {

    suspend fun saveLink(url: String): Result<Long> {
        val normalized = normalizePastedUrl(url)
        val fetched = when (val result = fetcher.fetch(normalized, etag = null, lastModified = null)) {
            is FetchResult.Success -> result
            is FetchResult.Failure -> return Result.failure(SaveLinkFailure.Unreachable(result.message))
            // No validators were sent, so nothing could have been validated.
            FetchResult.NotModified ->
                return Result.failure(SaveLinkFailure.Unreachable("Nothing came back from $normalized."))
        }

        // A pasted feed address is not an error — it is the other feature (§0.4). Checked
        // against the bytes we already have, not through discovery: a blog *post* routinely
        // declares its site's feed via autodiscovery, and that must not make every post look
        // like a feed.
        if (parser.parse(fetched.bytes, fetched.contentType, fetched.finalUrl) is ParseResult.Success) {
            return Result.failure(SaveLinkFailure.IsFeed(fetched.finalUrl))
        }

        val document = parseHtml(fetched.bytes, fetched.finalUrl)
            ?: return Result.failure(SaveLinkFailure.Unreachable("$normalized is not a readable page."))

        val savedFeedId = feedDao.findByUrl(FeedEntity.SAVED_LINKS_FEED_URL)?.id
            ?: error("The saved-links feed is missing; every database is seeded with it (Y02).")

        val content = PageContentExtractor.extract(document, fetched.finalUrl)
        val now = clock.millis()
        val entity = EntryEntity(
            feedId = savedFeedId,
            guid = fetched.finalUrl,
            // A page with no title at all (no `<head>`, U01's corpus has several) still
            // saves — the reader can still open it by its address.
            title = content.metadata.title ?: fetched.finalUrl,
            link = fetched.finalUrl,
            author = null,
            publishedAt = content.metadata.publishedAt?.toEpochMilli() ?: now,
            publishedIsEstimated = content.metadata.publishedAt == null,
            summary = HtmlSanitizer.summarize(content.bodyHtml),
            contentHtml = content.bodyHtml,
            imageUrl = content.bodyHtml?.let { LeadImage.fromBody(it, fetched.finalUrl) }
                ?: content.ogImageUrl,
            readAt = null,
            isSaved = true,
            savedAt = now,
            fetchedAt = now,
        )

        // Idempotent on (feedId, guid), same as a refetched feed entry — pasting the same
        // link twice lands on the row this already wrote rather than duplicating it.
        entryDao.upsertAll(listOf(entity))
        val saved = entryDao.findByGuid(savedFeedId, fetched.finalUrl)
            ?: error("upsertAll just wrote this row.")
        return Result.success(saved.id)
    }

    /** Same shape as [ArticleTextRepository]'s own: jsoup sniffs the page's own charset. */
    private fun parseHtml(bytes: ByteArray, baseUrl: String): Document? =
        runCatching { Jsoup.parse(bytes.inputStream(), null, baseUrl) }.getOrNull()
}
