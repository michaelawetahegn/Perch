package dev.mkiros.perch.data.repo

import dev.mkiros.perch.data.db.EntryDao
import dev.mkiros.perch.data.db.FeedDao
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.db.entity.FolderEntity
import dev.mkiros.perch.data.net.FeedFetcher
import dev.mkiros.perch.data.net.FetchResult
import dev.mkiros.perch.data.parse.FeedDiscovery
import dev.mkiros.perch.data.parse.FeedParser
import dev.mkiros.perch.data.parse.FetchedPage
import dev.mkiros.perch.data.parse.HtmlSanitizer
import dev.mkiros.perch.data.parse.ParseResult
import dev.mkiros.perch.data.parse.ParsedEntry
import dev.mkiros.perch.data.parse.ParsedFeed
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
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

/**
 * What the address the user pasted turned out to be.
 *
 * Resolving is deliberately separate from committing: the add-source sheet shows the
 * feed's own title and entry count as confirmation *before* anything is subscribed to
 * (DESIGN.md §5), and every way this can go wrong is a value the sheet renders inline
 * rather than an exception it has to catch.
 */
sealed interface SourceResolution {

    /** A real feed, fetched and parsed but not yet subscribed to. Commit with [FeedRepository.add]. */
    class Resolved internal constructor(
        /** The address to poll — post-discovery, post-redirect. */
        val feedUrl: String,
        val title: String,
        val siteUrl: String?,
        internal val parsed: ParsedFeed,
        internal val etag: String?,
        internal val lastModified: String?,
    ) : SourceResolution {

        /** What the sheet shows as confirmation, alongside [title]. */
        val entryCount: Int get() = parsed.entries.size
    }

    /** This feed is already subscribed to — [feedId] is the source the drawer already lists. */
    data class AlreadySubscribed(val feedId: Long, val title: String) : SourceResolution

    /** Reachable, but neither a feed nor a page that leads to one. */
    data class NoFeedFound(val url: String) : SourceResolution

    /** Could not be fetched at all; [message] is already phrased for the user. */
    data class Unreachable(val message: String) : SourceResolution
}

/** What a whole refresh pass came to, keyed by feed id. */
data class RefreshReport(val outcomes: Map<Long, FeedRefreshOutcome>) {

    val newEntries: Int
        get() = outcomes.values.sumOf { (it as? FeedRefreshOutcome.Updated)?.newEntries ?: 0 }

    val failed: Int
        get() = outcomes.values.count { it is FeedRefreshOutcome.Failed }
}

/**
 * Sources: subscribing to them, and refreshing them — fetch → parse → dedupe → upsert →
 * record health. Both halves share one fetcher, one parser and one notion of what a
 * duplicate is, which is why they live together.
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
    private val discovery: FeedDiscovery = FeedDiscovery(fetcher, parser),
) {

    /**
     * How many sources are subscribed, reactively. An empty reading list means one thing
     * with zero sources and another with forty (DESIGN.md §7), and this is how home
     * tells them apart.
     */
    fun observeSourceCount(): Flow<Int> = feedDao.observeCount().distinctUntilChanged()

    /**
     * Every subscribed source in drawer order (DESIGN.md §5). Carries the whole row, so
     * the drawer can render its own health — `lastError` is what the `⚠` is drawn from.
     */
    fun observeSources(): Flow<List<FeedEntity>> = feedDao.observeAll().distinctUntilChanged()

    /**
     * One source by id, or null if it has been removed. The article byline needs the name
     * behind an entry, and an entry only carries its `feedId`.
     */
    suspend fun find(feedId: Long): FeedEntity? = feedDao.findById(feedId)

    // ---- subscribing -----------------------------------------------------------

    /**
     * Works out what [url] is, without subscribing to anything. One round trip in the
     * common case: an address that already parses as a feed skips discovery entirely
     * (SPEC.md §5), and an address we already poll is recognised before it is fetched.
     */
    suspend fun resolve(url: String): SourceResolution {
        val pasted = url.trim()
        subscribedTo(pasted)?.let { return it }

        val landing = when (val fetched = fetcher.fetch(pasted, etag = null, lastModified = null)) {
            is FetchResult.Success -> fetched
            is FetchResult.Failure -> return SourceResolution.Unreachable(fetched.message)
            // Unreachable in practice: we send no validators, so nothing can be validated.
            FetchResult.NotModified -> return nothingCameBack(pasted)
        }
        resolutionOf(landing)?.let { return it }

        val declared = discovery.resolve(pasted, landing.asPage())
            ?: return SourceResolution.NoFeedFound(pasted)

        val feedPage = when (val fetched = fetcher.fetch(declared, etag = null, lastModified = null)) {
            is FetchResult.Success -> fetched
            is FetchResult.Failure -> return SourceResolution.Unreachable(fetched.message)
            FetchResult.NotModified -> return nothingCameBack(declared)
        }
        return resolutionOf(feedPage) ?: SourceResolution.NoFeedFound(pasted)
    }

    /**
     * Subscribes to a [resolved] feed and stores the entries that resolving already
     * fetched — adding a source costs one round trip, not two.
     *
     * Idempotent on `feedUrl`: confirm-then-commit leaves a window in which the same feed
     * could arrive twice (a second sheet, an OPML import), and `feedUrl` is uniquely
     * indexed, so the second commit joins the existing source rather than aborting. A
     * source that was already subscribed keeps the folder it is already in — [folderId]
     * says where a *new* source lands, it is not a move.
     */
    suspend fun add(
        resolved: SourceResolution.Resolved,
        folderId: Long = FolderEntity.UNCATEGORIZED_ID,
    ): Long {
        val addedAt = clock.millis()
        val feedId = feedDao.findByUrl(resolved.feedUrl)?.id ?: feedDao.insert(
            FeedEntity(
                folderId = folderId,
                feedUrl = resolved.feedUrl,
                siteUrl = resolved.siteUrl,
                title = resolved.title,
                customTitle = null,
                faviconUrl = null,
                etag = resolved.etag,
                lastModified = resolved.lastModified,
                lastFetchedAt = addedAt,
                lastSuccessAt = addedAt,
                lastError = null,
                addedAt = addedAt,
            ),
        )
        entryDao.upsertAll(
            resolved.parsed.entries
                .distinctBy { it.guid }
                .map { it.toEntity(feedId, resolved.parsed, resolved.feedUrl, addedAt) },
        )
        return feedId
    }

    /** Unsubscribes. The source's entries go with it, via `ON DELETE CASCADE`. */
    suspend fun remove(feedId: Long) = feedDao.deleteById(feedId)

    /**
     * Unsubscribes a whole batch (U09a). One statement rather than a loop of [remove], so
     * the drawer and the list settle once instead of flickering through N intermediate
     * subscription lists — and so a failure cannot leave half the batch deleted.
     *
     * There is no undo: the entries go with the sources, saved and liked ones included,
     * which is why the caller confirms with a dialog that names what is about to go.
     */
    suspend fun removeAll(feedIds: Collection<Long>) {
        if (feedIds.isEmpty()) return
        feedDao.deleteByIds(feedIds.toList())
    }

    /**
     * Renames a source for display only. The feed's own [FeedEntity.title] is left alone —
     * it is what the next refresh overwrites, and what a cleared rename falls back to, so
     * a blank [name] restores it rather than leaving the drawer with an empty label.
     */
    suspend fun rename(feedId: Long, name: String?) =
        feedDao.setCustomTitle(feedId, name?.trim()?.takeIf { it.isNotEmpty() })

    /** The resolution [page] represents, or null if it is not a feed at all. */
    private suspend fun resolutionOf(page: FetchResult.Success): SourceResolution? {
        val parsed = parser.parse(page.bytes, page.contentType, page.finalUrl)
        if (parsed !is ParseResult.Success) return null
        // Against the *final* URL: a redirect is how two pasted addresses converge on one
        // feed, and that is a duplicate as surely as pasting the same address twice.
        return subscribedTo(page.finalUrl) ?: SourceResolution.Resolved(
            feedUrl = page.finalUrl,
            title = parsed.feed.title,
            siteUrl = parsed.feed.siteUrl,
            parsed = parsed.feed,
            etag = page.etag,
            lastModified = page.lastModified,
        )
    }

    private suspend fun subscribedTo(feedUrl: String): SourceResolution.AlreadySubscribed? =
        feedDao.findByUrl(feedUrl)?.let {
            SourceResolution.AlreadySubscribed(it.id, it.customTitle ?: it.title)
        }

    private fun nothingCameBack(url: String) =
        SourceResolution.Unreachable("Nothing came back from $url.")

    private fun FetchResult.Success.asPage() = FetchedPage(bytes, contentType, finalUrl)

    // ---- refreshing ------------------------------------------------------------

    /** Refreshes every source, at most [concurrency] in flight, failures isolated. */
    suspend fun refreshAll(): RefreshReport = refresh(feedDao.getAll())

    /**
     * Refreshes every source the background pass is allowed to poll right now.
     *
     * This is [refreshAll] minus the sources that keep failing: SPEC.md §7 puts a feed
     * that has failed [SICK_AFTER] times in a row on a [SICK_FLOOR] floor until it
     * succeeds, so a host that has been down for a week costs one request every six
     * hours instead of one every hour. Manual refresh deliberately does not go through
     * here — a user pulling to refresh is asking for exactly this feed, now.
     */
    suspend fun refreshDue(): RefreshReport {
        val now = clock.millis()
        return refresh(feedDao.getAll().filter { it.isDue(now) })
    }

    private fun FeedEntity.isDue(now: Long): Boolean {
        if (consecutiveFailures < SICK_AFTER) return true
        val last = lastFetchedAt ?: return true
        return now - last >= SICK_FLOOR.toMillis()
    }

    /**
     * Refreshes the sources filed under one folder (U06). A pull-to-refresh refreshes the
     * scope on screen, and with the drawer scoped to a folder that scope is this.
     */
    suspend fun refreshFolder(folderId: Long): RefreshReport =
        refresh(feedDao.getByFolder(folderId))

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

        /** SPEC.md §7: this many failures in a row and the source is polled sparingly. */
        const val SICK_AFTER = 5

        /** SPEC.md §7: how sparingly. */
        val SICK_FLOOR: Duration = Duration.ofHours(6)
    }
}
