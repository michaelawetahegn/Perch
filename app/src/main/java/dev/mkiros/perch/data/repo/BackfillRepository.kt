package dev.mkiros.perch.data.repo

import dev.mkiros.perch.data.archive.ArchiveDiscovery
import dev.mkiros.perch.data.archive.ArchivePost
import dev.mkiros.perch.data.archive.RobotsRules
import dev.mkiros.perch.data.archive.hostRoot
import dev.mkiros.perch.data.db.EntryDao
import dev.mkiros.perch.data.db.FeedDao
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.extract.PageContentExtractor
import dev.mkiros.perch.data.parse.HtmlSanitizer
import dev.mkiros.perch.data.parse.LeadImage
import dev.mkiros.perch.data.parse.PageFetcher
import java.time.Clock
import java.time.Instant
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** What [BackfillRepository.plan] found, before anything is fetched. */
data class BackfillPlan(
    val feedId: Long,
    /** New posts this run would fetch — already deduped against what is stored, capped
     *  at [BackfillRepository.MAX_PAGES]. What the reader is told *will* happen. */
    val toFetch: List<ArchivePost>,
    /** How many not-yet-stored posts discovery found, uncapped — what [isWorthwhile] and
     *  the offer's "N more posts" both read. */
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
 * set) means a post already stored the run before is skipped, not refetched. That single
 * property is what makes a cancelled run resumable and a repeated one a no-op: there is no
 * separate "resume point" to track, only what is and is not in `entries` yet.
 *
 * Every page fetch reuses [PageContentExtractor] — the one function PLAN-6 Y03 lifted out
 * of [ArticleTextRepository], the same one [SavedLinkRepository] calls. No second metadata
 * or extraction path (PLAN-7 §0.2).
 */
class BackfillRepository(
    private val feedDao: FeedDao,
    private val entryDao: EntryDao,
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

        val feedPage = fetcher.fetch(feed.feedUrl)
        val discovered = discovery.discover(feed.siteUrl ?: feed.feedUrl, feedPage)
        val stored = entryDao.guidsForFeed(feedId).toHashSet()
        val fresh = discovered.filterNot { it.url in stored }
        val reach = entryDao.reach(feedId)

        return BackfillPlan(
            feedId = feedId,
            toFetch = fresh.take(MAX_PAGES),
            newPostCount = fresh.size,
            isWorthwhile = fresh.isNotEmpty() && fresh.size >= reach.entryCount * MATERIALLY_MORE_FACTOR,
        )
    }

    /**
     * Fetches [BackfillPlan.toFetch] in order, one at a time, [politeDelayMillis] apart,
     * skipping anything `robots.txt` disallows. [isCancelled] is polled between pages, not
     * mid-fetch — a reader who asks Perch to stop gets to keep whatever already landed
     * (§0.3), not a half-written row.
     */
    suspend fun run(
        feedId: Long,
        isCancelled: suspend () -> Boolean = { false },
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): BackfillResult {
        val feed = feedDao.findById(feedId) ?: return EMPTY_RESULT
        val plan = plan(feedId) ?: return EMPTY_RESULT
        if (plan.toFetch.isEmpty()) return EMPTY_RESULT

        val robots = robotsRules(feed)
        var stored = 0
        var skipped = 0
        var failed = 0
        for ((index, post) in plan.toFetch.withIndex()) {
            if (isCancelled()) break
            if (robots.disallows(post.url)) {
                skipped++
            } else {
                if (index > 0) delay(politeDelayMillis)
                if (runCatching { fetchAndStore(feedId, post) }.getOrDefault(false)) stored++ else failed++
            }
            onProgress(index + 1, plan.toFetch.size)
        }
        return BackfillResult(attempted = plan.toFetch.size, stored = stored, skippedByRobots = skipped, failed = failed)
    }

    private suspend fun fetchAndStore(feedId: Long, post: ArchivePost): Boolean {
        val fetched = fetcher.fetch(post.url) ?: return false
        val document = parseHtml(fetched.bytes, fetched.finalUrl) ?: return false
        val content = PageContentExtractor.extract(document, fetched.finalUrl)
        val (publishedAt, estimated) = backfillDate(content.metadata.publishedAt, post.lastmod)

        entryDao.upsertAll(
            listOf(
                EntryEntity(
                    feedId = feedId,
                    guid = fetched.finalUrl,
                    title = content.metadata.title ?: fetched.finalUrl,
                    link = fetched.finalUrl,
                    author = null,
                    publishedAt = publishedAt,
                    publishedIsEstimated = estimated,
                    summary = HtmlSanitizer.summarize(content.bodyHtml),
                    contentHtml = content.bodyHtml,
                    imageUrl = content.bodyHtml?.let { LeadImage.fromBody(it, fetched.finalUrl) }
                        ?: content.ogImageUrl,
                    readAt = null,
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

    private suspend fun robotsRules(feed: FeedEntity): RobotsRules {
        val root = hostRoot(feed.siteUrl ?: feed.feedUrl) ?: return RobotsRules.NONE
        val page = fetcher.fetch("$root/robots.txt") ?: return RobotsRules.NONE
        return RobotsRules.parse(String(page.bytes, Charsets.UTF_8))
    }

    /** Same shape as every other caller of [PageContentExtractor]: jsoup sniffs the page's own charset. */
    private fun parseHtml(bytes: ByteArray, baseUrl: String): Document? =
        runCatching { Jsoup.parse(bytes.inputStream(), null, baseUrl) }.getOrNull()

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

        private val EMPTY_RESULT = BackfillResult(attempted = 0, stored = 0, skippedByRobots = 0, failed = 0)
    }
}
