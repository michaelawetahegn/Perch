package dev.mkiros.perch.data.repo

import dev.mkiros.perch.data.db.EntryDao
import dev.mkiros.perch.data.db.FeedDao
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.net.FeedFetcher
import dev.mkiros.perch.data.net.FetchResult
import dev.mkiros.perch.data.parse.FeedParser
import dev.mkiros.perch.data.parse.HtmlSanitizer
import dev.mkiros.perch.data.parse.ParseResult
import dev.mkiros.perch.data.parse.ParsedEntry
import dev.mkiros.perch.data.parse.ParsedFeed
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** What one feed's turn at refreshing came to. */
sealed interface FeedRefreshOutcome {

    /** The body was parsed and written; [newEntries] counts only rows that did not exist. */
    data class Updated(val newEntries: Int) : FeedRefreshOutcome

    /** The server answered `304`. Nothing was parsed and nothing was written. */
    data object Unchanged : FeedRefreshOutcome

    /** A per-source problem, already phrased for the drawer's `⚠`. */
    data class Failed(val message: String) : FeedRefreshOutcome
}

/** What a whole refresh pass came to, keyed by feed id. */
data class RefreshReport(val outcomes: Map<Long, FeedRefreshOutcome>) {

    val newEntries: Int
        get() = outcomes.values.sumOf { (it as? FeedRefreshOutcome.Updated)?.newEntries ?: 0 }

    val failed: Int
        get() = outcomes.values.count { it is FeedRefreshOutcome.Failed }
}

/**
 * Refreshing sources: fetch → parse → dedupe → upsert → record health.
 *
 * The whole design is about damage containment. Every feed's turn is wrapped so that a
 * failure — unreachable host, HTML where a feed should be, an oversized body, or an
 * outright exception — lands in that source's `lastError` and nowhere else; a refresh of
 * forty sources with four sick ones still writes the other thirty-six. Success is equally
 * conservative: entry identity is `(feedId, guid)`, so re-fetching an unchanged feed
 * writes zero new rows and leaves read state alone (see [EntryDao.upsertAll]).
 */
class FeedRepository(
    private val feedDao: FeedDao,
    private val entryDao: EntryDao,
    private val fetcher: FeedFetcher,
    private val clock: Clock,
    private val parser: FeedParser = FeedParser(),
    private val concurrency: Int = MAX_IN_FLIGHT,
) {

    /** Refreshes every source, at most [concurrency] in flight, failures isolated. */
    suspend fun refreshAll(): RefreshReport = refresh(feedDao.getAll())

    /** Refreshes one source. Returns [FeedRefreshOutcome.Failed] if it no longer exists. */
    suspend fun refresh(feedId: Long): FeedRefreshOutcome {
        val feed = feedDao.findById(feedId)
            ?: return FeedRefreshOutcome.Failed("That source is no longer subscribed.")
        return refreshOne(feed)
    }

    private suspend fun refresh(feeds: List<FeedEntity>): RefreshReport = coroutineScope {
        val permits = Semaphore(concurrency)
        val outcomes = feeds
            .map { feed -> async { feed.id to permits.withPermit { refreshOne(feed) } } }
            .awaitAll()
        RefreshReport(outcomes.toMap())
    }

    /**
     * One source's turn. Nothing thrown from here escapes: an unexpected exception is as
     * much a per-source failure as a 500 is, and a refresh pass that dies on one feed's
     * surprise is the bug this guards against.
     */
    private suspend fun refreshOne(feed: FeedEntity): FeedRefreshOutcome {
        val startedAt = clock.millis()
        val outcome = runCatching { fetchAndStore(feed, startedAt) }
            .getOrElse { FeedRefreshOutcome.Failed(it.message ?: it.javaClass.simpleName) }
        if (outcome is FeedRefreshOutcome.Failed) recordFailure(feed, outcome.message, startedAt)
        return outcome
    }

    private suspend fun fetchAndStore(feed: FeedEntity, startedAt: Long): FeedRefreshOutcome {
        val fetched = fetcher.fetch(feed.feedUrl, feed.etag, feed.lastModified)
        return when (fetched) {
            is FetchResult.Failure -> FeedRefreshOutcome.Failed(fetched.message)

            // A 304 is a success (SPEC.md §6): it parses nothing and writes no entries,
            // but it does prove the source is healthy, so the ⚠ has to clear here too.
            FetchResult.NotModified -> {
                mutate(feed.id) { it.succeeded(fetchedAt = startedAt) }
                FeedRefreshOutcome.Unchanged
            }

            is FetchResult.Success -> when (
                val parsed = parser.parse(fetched.bytes, fetched.contentType, fetched.finalUrl)
            ) {
                is ParseResult.Failure -> FeedRefreshOutcome.Failed(parsed.reason)
                is ParseResult.Success -> store(feed, parsed.feed, fetched, startedAt)
            }
        }
    }

    private suspend fun store(
        feed: FeedEntity,
        parsed: ParsedFeed,
        fetched: FetchResult.Success,
        startedAt: Long,
    ): FeedRefreshOutcome {
        // First guid wins: a feed that lists the same article twice is describing one
        // article, and the earlier position is the one it considers current.
        val deduped = parsed.entries.distinctBy { it.guid }
        val newEntries = entryDao.upsertAll(
            deduped.map { it.toEntity(feed.id, parsed, fetched.finalUrl, startedAt) },
        )

        mutate(feed.id) { current ->
            current.succeeded(fetchedAt = startedAt).copy(
                // The parsed title is the feed's own; customTitle is the user's rename
                // and is never touched here.
                title = parsed.title,
                siteUrl = parsed.siteUrl ?: current.siteUrl,
                feedUrl = pollFrom(current, fetched.finalUrl),
                etag = fetched.etag,
                lastModified = fetched.lastModified,
            )
        }

        entryDao.deleteReadOlderThan(
            feedId = feed.id,
            publishedBefore = startedAt - RETENTION.toMillis(),
            fetchedBefore = startedAt,
        )
        return FeedRefreshOutcome.Updated(newEntries)
    }

    /**
     * The address to poll next time. A redirect moves it, but `feedUrl` is uniquely
     * indexed — two subscriptions converging on one address would otherwise abort the
     * refresh with a constraint violation, so the newcomer keeps polling its old URL and
     * T16's duplicate handling can deal with the merge.
     */
    private suspend fun pollFrom(feed: FeedEntity, finalUrl: String): String = when {
        finalUrl == feed.feedUrl -> feed.feedUrl
        feedDao.findByUrl(finalUrl) != null -> feed.feedUrl
        else -> finalUrl
    }

    private suspend fun recordFailure(feed: FeedEntity, message: String, fetchedAt: Long) {
        mutate(feed.id) { current ->
            current.copy(
                lastFetchedAt = fetchedAt,
                lastError = message,
                consecutiveFailures = current.consecutiveFailures + 1,
            )
        }
    }

    /**
     * Applies [change] to the row as it stands *now*, not to the snapshot this refresh
     * started from. A fetch takes seconds, and in that window the user may have renamed
     * or reordered the source; writing back a whole stale row would quietly undo it.
     * Dropped silently if the source was removed mid-fetch — that is an answer, not an
     * error.
     */
    private suspend fun mutate(feedId: Long, change: suspend (FeedEntity) -> FeedEntity) {
        val current = feedDao.findById(feedId) ?: return
        feedDao.update(change(current))
    }

    private fun FeedEntity.succeeded(fetchedAt: Long) = copy(
        lastFetchedAt = fetchedAt,
        lastSuccessAt = fetchedAt,
        lastError = null,
        consecutiveFailures = 0,
    )

    /**
     * The parsed entry as a row. Sanitizing happens here rather than in the parsers so
     * that what reaches the database is already safe to render — the article screen never
     * sees feed-authored markup.
     */
    private fun ParsedEntry.toEntity(
        feedId: Long,
        parsed: ParsedFeed,
        finalUrl: String,
        fetchedAt: Long,
    ): EntryEntity {
        val baseUrl = link ?: parsed.siteUrl ?: finalUrl
        val safeHtml = HtmlSanitizer.sanitize(contentHtml, baseUrl)
        return EntryEntity(
            feedId = feedId,
            guid = guid,
            title = title,
            link = link,
            author = author,
            // Last resort only: the parsers already fall back to the feed's own date.
            publishedAt = publishedAt?.toEpochMilli() ?: fetchedAt,
            publishedIsEstimated = publishedIsEstimated || publishedAt == null,
            summary = HtmlSanitizer.summarize(safeHtml),
            contentHtml = safeHtml,
            imageUrl = imageUrl,
            readAt = null,
            fetchedAt = fetchedAt,
        )
    }

    private companion object {
        /** SPEC.md §6: four feeds in flight, no more. */
        const val MAX_IN_FLIGHT = 4

        /** SPEC.md §7: read entries are kept this long, unread ones forever. */
        val RETENTION: Duration = Duration.ofDays(30)
    }
}
