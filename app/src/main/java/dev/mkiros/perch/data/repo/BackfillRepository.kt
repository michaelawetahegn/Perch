package dev.mkiros.perch.data.repo

import dev.mkiros.perch.data.archive.ArchiveDiscovery
import dev.mkiros.perch.data.archive.ArchivePost
import dev.mkiros.perch.data.archive.RobotsRules
import dev.mkiros.perch.data.db.ArchivePostDao
import dev.mkiros.perch.data.db.EntryDao
import dev.mkiros.perch.data.db.FeedDao
import dev.mkiros.perch.data.db.entity.ArchivePostEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.extract.PageContentExtractor
import dev.mkiros.perch.data.extract.toEntry
import dev.mkiros.perch.data.db.EntryIdentity
import dev.mkiros.perch.data.parse.PageFetcher
import dev.mkiros.perch.data.parse.urlKey
import dev.mkiros.perch.rethrowCancellation
import java.time.Clock
import java.time.Instant

/** What [BackfillRepository.plan] found, before anything is fetched. */
data class BackfillPlan(
    val feedId: Long,
    /** The next batch: the newest [BackfillRepository.MAX_PAGES] remembered posts no run has
     *  dealt with yet. What the reader is told *will* happen. */
    val toFetch: List<ArchivePost>,
    /** How many remembered posts no run has dealt with yet, uncapped — what [isWorthwhile],
     *  the offer's "N more posts" and the end-of-list footer all read. */
    val newPostCount: Int,
    /** PLAN-7 §0.3: earned, not constant — true only when the archive plainly holds
     *  materially more than the feed already gave us. */
    val isWorthwhile: Boolean,
)

/** What one backfill run came to. */
data class BackfillResult(
    val attempted: Int,
    val stored: Int,
    val skippedByRobots: Int,
    val failed: Int,
)

/**
 * Fills a source's history in behind its feed (PLAN-7 §0.3, issue #21) — [ArchiveDiscovery]
 * finds the candidate URLs, this fetches the ones not already stored and writes them as
 * ordinary entries under the source's own [dev.mkiros.perch.data.db.entity.FeedEntity.id].
 *
 * Never automatic: [plan] is a read-only preview a caller (Z03's UI, or the worker below)
 * decides whether to act on. [run] is always safe to call again — it starts from [plan]
 * every time, and `(feedId, guid)` idempotency (guid = final URL, the same convention Y03
 * set) means a post already stored the run before is skipped, not refetched.
 *
 * **The plan is remembered** (PLAN-12 §0.4, #68). Discovery runs once per source and again
 * only when what it remembered is older than [REDISCOVER_AFTER_MILLIS]; everything it finds
 * lands in `archive_posts` and is never deleted by a later discovery. The cursor into the
 * archive is that table's `fetchedAt` stamp, not the `entries` table: a run stamps every
 * page it fetched, found already stored, or gave up on for good, so retention pruning an
 * article the reader has read cannot make a later batch download it again. What is left
 * unstamped is the next batch — [MAX_PAGES] at a time, newest first — which is what lets
 * the reader keep scrolling into a 12,000-post archive forty posts at a time.
 *
 * A candidate is compared, as a [urlKey], against every stored guid *and* link (PLAN-12
 * §0.2, #69): the feed poll keeps WordPress's `?p=N` guid and the page's address as the link,
 * so a guid-only check fetched every post the feed had already given us a second time.
 *
 * Every page fetch reuses [PageContentExtractor] — the one function PLAN-6 Y03 lifted out
 * of [ArticleTextRepository], the same one [SavedLinkRepository] calls. No second metadata
 * or extraction path (PLAN-7 §0.2).
 */
class BackfillRepository(
    private val feedDao: FeedDao,
    private val entryDao: EntryDao,
    private val archivePostDao: ArchivePostDao,
    private val fetcher: PageFetcher,
    private val clock: Clock,
    private val discovery: ArchiveDiscovery = ArchiveDiscovery(fetcher),
    /** Seams for a test to run instantly rather than for real — production never overrides these. */
    private val delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val politeDelayMillis: Long = DEFAULT_DELAY_MILLIS,
) {

    /** A read-only look at what [run] would do for [feedId] — no fetch of a candidate page. */
    suspend fun plan(feedId: Long): BackfillPlan? {
        val feed = feedDao.findById(feedId) ?: return null
        if (feed.isSynthetic) return null
        return plan(feed, RobotsRules.fetch(fetcher, feed.siteUrl ?: feed.feedUrl))
    }

    /**
     * The plan proper, over a `robots.txt` the caller has already read — the file carries
     * both the `Sitemap:` discovery starts from and the `Disallow:` rules [run] skips by,
     * so a run reads it once and hands the same rules to both.
     */
    private suspend fun plan(feed: FeedEntity, robots: RobotsRules): BackfillPlan {
        val feedId = feed.id
        if (needsDiscovery(feedId)) discover(feed, robots)
        val reach = entryDao.reach(feedId)
        val newPostCount = archivePostDao.countUnfetched(feedId)

        return BackfillPlan(
            feedId = feedId,
            toFetch = archivePostDao.unfetched(feedId, MAX_PAGES).map { it.toArchivePost() },
            newPostCount = newPostCount,
            isWorthwhile = newPostCount > 0 && newPostCount >= reach.entryCount * MATERIALLY_MORE_FACTOR,
        )
    }

    private suspend fun needsDiscovery(feedId: Long): Boolean {
        val newest = archivePostDao.newestDiscoveredAt(feedId) ?: return true
        return clock.millis() - newest > REDISCOVER_AFTER_MILLIS
    }

    /**
     * Runs discovery and remembers what it found. Every post is added and none removed — a
     * sitemap that shrinks does not forget history — and a post the feed already gave us
     * (matched as a [urlKey] against every stored guid and link) is stamped fetched on the
     * spot, so the first plan after adding a source already has its feed items marked.
     */
    private suspend fun discover(feed: FeedEntity, robots: RobotsRules) {
        val feedId = feed.id
        val now = clock.millis()
        val feedPage = fetcher.fetch(feed.feedUrl)
        val discovered = discovery.discover(feed.siteUrl ?: feed.feedUrl, feedPage, robots)
        val stored = entryDao.identitiesForFeed(feedId).urlKeys()
        archivePostDao.upsertIgnore(
            discovered.map {
                ArchivePostEntity(
                    feedId = feedId,
                    url = it.url,
                    lastmod = it.lastmod?.toEpochMilli(),
                    discoveredAt = now,
                    fetchedAt = null,
                )
            },
        )
        archivePostDao.markFetched(feedId, discovered.filter { urlKey(it.url) in stored }.map { it.url }, now)
    }

    /**
     * Fetches [BackfillPlan.toFetch] in order, one at a time, [politeDelayMillis] apart,
     * skipping anything `robots.txt` disallows. [isCancelled] is polled between pages, not
     * mid-fetch — a reader who asks Perch to stop gets to keep whatever already landed
     * (§0.3), not a half-written row.
     *
     * Each page's stamp is written as it is dealt with, never in one batch at the end, so a
     * cancelled run keeps its place too. A page that could not be fetched is left unstamped
     * for a later batch to retry; one `robots.txt` forbids is stamped, because asking again
     * will get the same answer and an unstamped forbidden page would hold its slot in the
     * batch for ever.
     */
    suspend fun run(
        feedId: Long,
        isCancelled: suspend () -> Boolean = { false },
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): BackfillResult {
        val feed = feedDao.findById(feedId) ?: return EMPTY_RESULT
        if (feed.isSynthetic) return EMPTY_RESULT

        val robots = RobotsRules.fetch(fetcher, feed.siteUrl ?: feed.feedUrl)
        val plan = plan(feed, robots)
        if (plan.toFetch.isEmpty()) return EMPTY_RESULT
        val stored = entryDao.identitiesForFeed(feedId).urlKeys()

        var storedCount = 0
        var skipped = 0
        var failed = 0
        for ((index, post) in plan.toFetch.withIndex()) {
            if (isCancelled()) break
            val done = when {
                robots.disallows(post.url) -> { skipped++; true }
                // The feed listed it since discovery: nothing to fetch, but the batch moves on.
                urlKey(post.url) in stored -> true
                else -> {
                    if (index > 0) delay(politeDelayMillis)
                    val ok = runCatching { fetchAndStore(feedId, post) }
                        // A cancelled fetch is the reader stopping us, not a page that failed:
                        // it belongs to the caller, not to this run's tally.
                        .rethrowCancellation()
                        .getOrDefault(false)
                    if (ok) storedCount++ else failed++
                    ok
                }
            }
            if (done) archivePostDao.markFetched(feedId, listOf(post.url), clock.millis())
            onProgress(index + 1, plan.toFetch.size)
        }
        return BackfillResult(attempted = plan.toFetch.size, stored = storedCount, skippedByRobots = skipped, failed = failed)
    }

    private suspend fun fetchAndStore(feedId: Long, post: ArchivePost): Boolean {
        val fetched = fetcher.fetch(post.url) ?: return false
        // The plan matched the sitemap's spelling; a redirect may land on an address the feed
        // already stored under another one. `upsertAll` would merge it, but there is no point
        // extracting a page whose row exists.
        if (fetched.finalUrl != post.url && entryDao.findByGuidOrLink(feedId, fetched.finalUrl, fetched.finalUrl) != null) {
            return true
        }
        val document = PageContentExtractor.parse(fetched.bytes, fetched.finalUrl) ?: return false
        val content = PageContentExtractor.extract(document, fetched.finalUrl)
        val (publishedAt, estimated) = backfillDate(content.metadata.publishedAt, post.lastmod)

        entryDao.upsertAll(
            listOf(
                content.toEntry(
                    feedId = feedId,
                    finalUrl = fetched.finalUrl,
                    publishedAt = publishedAt,
                    publishedIsEstimated = estimated,
                    fetchedAt = clock.millis(),
                ),
            ),
        )
        return true
    }

    /**
     * PLAN-7 §0.3a's date chain, one rung longer than a live fetch's: the page's own
     * metadata wins, the sitemap's `<lastmod>` is next, and only when both decline does a
     * backfilled post get a guessed date — [Instant.EPOCH], which sorts below everything
     * real by construction, so an undated post can never look newer than it is.
     */
    private fun backfillDate(metadataDate: Instant?, lastmod: Instant?): Pair<Long, Boolean> = when {
        metadataDate != null -> metadataDate.toEpochMilli() to false
        lastmod != null -> lastmod.toEpochMilli() to false
        else -> Instant.EPOCH.toEpochMilli() to true
    }

    companion object {
        /**
         * One background run fetches at most this many pages. Bounded so "add a source"
         * can never turn into an unattended download of a whole archive (§0.3); a rerun is
         * idempotent, so a reader can ask again for the rest.
         */
        const val MAX_PAGES = 40

        /**
         * The offer is earned only once the archive would at least double what the feed
         * already gave us — a handful of extra posts is not the confusion issue #21 was
         * about (§0.3, §0.4).
         */
        const val MATERIALLY_MORE_FACTOR = 2

        /** Politeness: a pause between each page fetch, never parallel (§0.3). */
        const val DEFAULT_DELAY_MILLIS = 500L

        /** A remembered plan older than this is discovered again (PLAN-12 §0.4). */
        const val REDISCOVER_AFTER_MILLIS = 7 * 24 * 60 * 60 * 1000L

        private val EMPTY_RESULT = BackfillResult(attempted = 0, stored = 0, skippedByRobots = 0, failed = 0)

        /** The [urlKey] of every guid and every non-null link — the set a candidate is looked up in. */
        internal fun List<EntryIdentity>.urlKeys(): HashSet<String> =
            flatMapTo(HashSet()) { listOfNotNull(it.guid, it.link).map(::urlKey) }

        private fun ArchivePostEntity.toArchivePost() =
            ArchivePost(url = url, lastmod = lastmod?.let(Instant::ofEpochMilli))
    }
}
