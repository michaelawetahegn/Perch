package dev.mkiros.perch.debug

import android.content.res.AssetManager
import android.util.Log
import dev.mkiros.perch.data.parse.FeedParser
import dev.mkiros.perch.data.parse.ParseResult
import dev.mkiros.perch.data.repo.FeedRepository
import dev.mkiros.perch.data.repo.SourceResolution
import java.time.Clock
import kotlinx.coroutines.flow.first

/**
 * Fills a fresh **debug** install with the T04 snapshots bundled under `assets/seed/`, so
 * that the first screen a screenshot or a hand-check ever sees is a real reading list.
 *
 * It exists because the design work downstream — T29's polish pass, T32's "do all 42
 * sources look like one publication?" gate — is a critique of pixels, and an empty state
 * cannot be critiqued. The snapshots are chosen for spread, not size: code-heavy posts
 * (nullprogram, regehr), an image-heavy one (ciechanow.ski), a long-tail archive with a
 * hundred terse titles (fabiensanglard), news with images (krebsonsecurity), and both
 * feed dialects.
 *
 * Two things it deliberately is not. It is not a fixture loader: entries go in through
 * [FeedRepository.add], the same path a real subscription takes, so seeded rows are
 * sanitized, summarized and deduped exactly like fetched ones and no screenshot flatters
 * the renderer with markup a real feed would never have survived. And it is not a reset:
 * it runs only when the reader has no sources at all ([seedIfEmpty]), so a source removed
 * during testing stays removed.
 *
 * Each source keeps its **real** feed URL, so pulling to refresh a seeded install polls
 * the live site and tops the list up rather than failing with a `⚠`.
 */
class DebugSeeder(
    private val assets: AssetManager,
    private val feeds: FeedRepository,
    private val clock: Clock,
    private val parser: FeedParser = FeedParser(),
) {

    /**
     * Seeds every bundled snapshot, or does nothing if any source is already subscribed.
     * Returns how many sources were added.
     *
     * A snapshot that fails to parse is skipped rather than thrown: a broken fixture must
     * not stop a debug build from launching, and the count in the log says what landed.
     */
    suspend fun seedIfEmpty(): Int {
        if (feeds.observeSourceCount().first() > 0) return 0

        var added = 0
        for ((fileName, feedUrl) in index()) {
            val bytes = runCatching { assets.open("$DIR/$fileName").use { it.readBytes() } }
                .onFailure { Log.w(TAG, "seed asset $fileName is unreadable", it) }
                .getOrNull()
                ?: continue
            when (val parsed = parser.parse(bytes, contentType = null, requestUrl = feedUrl)) {
                is ParseResult.Failure -> Log.w(TAG, "seed asset $fileName: ${parsed.reason}")
                is ParseResult.Success -> {
                    feeds.add(
                        SourceResolution.Resolved(
                            feedUrl = feedUrl,
                            title = parsed.feed.title,
                            siteUrl = parsed.feed.siteUrl,
                            parsed = parsed.feed,
                            // No validators: the next refresh should re-fetch in full,
                            // because these bytes are a snapshot, not something a server
                            // ever handed this install.
                            etag = null,
                            lastModified = null,
                        ),
                    )
                    added++
                }
            }
        }
        Log.i(TAG, "seeded $added sources at ${clock.millis()}")
        return added
    }

    /**
     * `<file>\t<feed url>` per line. The index rather than [AssetManager.list] decides
     * what gets seeded, because a snapshot on its own does not say where it came from and
     * the real URL is what makes a seeded source refreshable.
     */
    private fun index(): List<Pair<String, String>> =
        assets.open("$DIR/$INDEX").use { it.readBytes() }
            .decodeToString()
            .lineSequence()
            .mapNotNull { line ->
                val (file, url) = line.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
                file.trim() to url.trim()
            }
            .filter { (file, url) -> file.isNotEmpty() && url.isNotEmpty() }
            .toList()

    private companion object {
        const val DIR = "seed"
        const val INDEX = "index.tsv"
        const val TAG = "DebugSeeder"
    }
}
