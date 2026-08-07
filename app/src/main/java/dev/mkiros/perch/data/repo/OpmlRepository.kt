package dev.mkiros.perch.data.repo

import dev.mkiros.perch.data.db.FeedDao
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.opml.Opml
import dev.mkiros.perch.data.opml.OpmlOutline
import dev.mkiros.perch.data.opml.OpmlParse
import java.time.Clock

/** What an import came to — the `n added / m duplicates / k invalid` line SPEC.md §9 asks for. */
sealed interface OpmlImportResult {

    data class Imported(val added: Int, val duplicates: Int, val invalid: Int) : OpmlImportResult

    /** The file was not OPML; nothing was written. [message] is already phrased for the user. */
    data class Malformed(val message: String) : OpmlImportResult
}

/**
 * Subscriptions leaving and entering the app (SPEC.md §9).
 *
 * Import writes rows and stops — it does not fetch. Forty sources fetched inline would take
 * a minute and could half-fail; instead every imported source is left with no validators and
 * no fetch history, which is exactly the state [FeedRepository.refreshAll] treats as "never
 * polled". The caller triggers that one refresh afterwards.
 *
 * That also makes an import safe to repeat: identity is `feedUrl`, so re-importing yesterday's
 * file over today's library adds nothing, counts what it skipped, and cannot disturb a rename
 * or a read state.
 */
class OpmlRepository(
    private val feedDao: FeedDao,
    private val clock: Clock,
) {

    /** Every subscribed source as OPML 2.0, labelled the way the drawer labels it. */
    suspend fun export(): String = Opml.write(
        feedDao.getAll().map { OpmlOutline(it.customTitle ?: it.title, it.feedUrl, it.siteUrl) },
        createdAt = clock.instant(),
    )

    /** What to pre-fill the SAF create-document dialog with. */
    fun suggestedFileName(): String = Opml.fileName(clock.instant().atZone(clock.zone).toLocalDate())

    /** Adds every source in [text] that is not already subscribed to. Fetches nothing. */
    suspend fun import(text: String): OpmlImportResult {
        val parsed = when (val result = Opml.read(text)) {
            is OpmlParse.Success -> result
            is OpmlParse.Malformed -> return OpmlImportResult.Malformed(result.message)
        }

        val addedAt = clock.millis()
        var added = 0
        var duplicates = 0
        for (outline in parsed.outlines) {
            // Re-read per outline rather than snapshotting: this is also what makes a file
            // that lists the same feed under two folders import it once.
            if (feedDao.findByUrl(outline.xmlUrl) != null) {
                duplicates++
                continue
            }
            feedDao.insert(
                FeedEntity(
                    feedUrl = outline.xmlUrl,
                    siteUrl = outline.siteUrl,
                    title = outline.title,
                    customTitle = null,
                    faviconUrl = null,
                    etag = null,
                    lastModified = null,
                    lastFetchedAt = null,
                    lastSuccessAt = null,
                    lastError = null,
                    addedAt = addedAt,
                ),
            )
            added++
        }
        return OpmlImportResult.Imported(added = added, duplicates = duplicates, invalid = parsed.invalid)
    }
}
