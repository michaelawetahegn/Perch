package dev.mkiros.perch.data.repo

import dev.mkiros.perch.data.db.FeedDao
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.db.entity.FolderEntity
import dev.mkiros.perch.data.opml.Opml
import dev.mkiros.perch.data.opml.OpmlOutline
import dev.mkiros.perch.data.opml.OpmlParse
import java.time.Clock

/**
 * What an import came to — the `n added / m duplicates / k invalid / f folders` line
 * SPEC.md §9 asks for.
 */
sealed interface OpmlImportResult {

    /**
     * @param folders how many folders the import had to **create**. Not how many it filed
     *   sources into: re-importing the same file has to report zero of everything, and a
     *   folder that already existed was not a change.
     */
    data class Imported(
        val added: Int,
        val duplicates: Int,
        val invalid: Int,
        val folders: Int,
    ) : OpmlImportResult

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
 * file over today's library adds nothing, counts what it skipped, and cannot disturb a rename,
 * a read state, or the folder a source has already been filed under.
 */
class OpmlRepository(
    private val feedDao: FeedDao,
    private val folders: FolderRepository,
    private val clock: Clock,
) {

    /**
     * Every subscribed source as OPML 2.0 (U13), labelled the way the drawer labels it and
     * nested under the folder the drawer nests it under.
     *
     * Walking folders rather than sources is what makes the document come out in drawer
     * order for free, Uncategorized last — and Uncategorized's sources come out unfiled,
     * because it is Perch's word for "no folder", not a folder anyone chose.
     */
    suspend fun export(): String = Opml.write(
        folders.folders().flatMap { folder ->
            val name = folder.name.takeUnless { folder.id == FolderEntity.UNCATEGORIZED_ID }
            feedDao.getByFolder(folder.id).map {
                OpmlOutline(it.customTitle ?: it.title, it.feedUrl, it.siteUrl, name)
            }
        },
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
        var foldersCreated = 0
        // Folder name (case-folded, as `createFolder` matches) → the id it resolved to.
        val resolved = mutableMapOf<String, Long>()
        for (outline in parsed.outlines) {
            // Re-read per outline rather than snapshotting: this is also what makes a file
            // that lists the same feed under two folders import it once. A source already
            // subscribed to is left entirely alone, folder included — an import adds
            // subscriptions, it does not refile a library the reader has organised.
            if (feedDao.findByUrl(outline.xmlUrl) != null) {
                duplicates++
                continue
            }
            // Resolved here rather than up front, so a folder is created only once a source
            // is actually going into it. A file whose "Podcasts" folder holds nothing but
            // feeds already subscribed to must not leave an empty row in the drawer.
            val folderId = outline.folder?.let { name ->
                resolved.getOrPut(name.trim().lowercase()) {
                    folders.findFolderNamed(name)?.id
                        ?: folders.createFolder(name).also { foldersCreated++ }
                }
            } ?: FolderEntity.UNCATEGORIZED_ID
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
                    folderId = folderId,
                ),
            )
            added++
        }
        return OpmlImportResult.Imported(
            added = added,
            duplicates = duplicates,
            invalid = parsed.invalid,
            folders = foldersCreated,
        )
    }
}
