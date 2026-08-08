package dev.mkiros.perch.data.repo

import dev.mkiros.perch.data.db.EntryDao
import dev.mkiros.perch.data.db.FeedDao
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.db.entity.FolderEntity
import dev.mkiros.perch.data.db.entity.PendingEntryStateEntity
import dev.mkiros.perch.data.profile.Profile
import dev.mkiros.perch.data.profile.ProfileEntryState
import dev.mkiros.perch.data.profile.ProfileFolder
import dev.mkiros.perch.data.profile.ProfileJson
import dev.mkiros.perch.data.profile.ProfileParse
import dev.mkiros.perch.data.profile.ProfileSource
import java.time.Clock

/** What a restore came to. */
sealed interface ProfileImportResult {

    /**
     * @param sourcesAdded subscriptions the library did not have.
     * @param sourcesExisting subscriptions it already had, left entirely alone.
     * @param foldersCreated folders the file brought that the library did not have.
     * @param stateApplied entries whose read/liked/saved state landed on an article that
     *   was already here.
     * @param statePending entries whose state is parked because the article has not been
     *   fetched yet. It is not a failure — it is the normal shape of a restore on a fresh
     *   install, where every single one is pending until the first refresh.
     */
    data class Restored(
        val sourcesAdded: Int,
        val sourcesExisting: Int,
        val foldersCreated: Int,
        val stateApplied: Int,
        val statePending: Int,
    ) : ProfileImportResult

    /** The file was not a Perch profile; nothing was written. */
    data class Malformed(val message: String) : ProfileImportResult

    /** Written by a later Perch than this one; nothing was written. */
    data class UnsupportedVersion(val found: Int, val supported: Int) : ProfileImportResult
}

/**
 * Backup and restore of a whole reading identity (U14).
 *
 * OPML moves subscriptions between *readers*; this moves a reader between *phones*. The
 * difference is everything a subscription list cannot carry — which articles you had read,
 * which you liked, which are still queued — and that is the part a reinstall actually
 * destroys, because the sources can always be pasted back and the state cannot.
 *
 * Restore is a **merge**, and three properties follow from that and are what the tests
 * pin:
 * - It never removes. A source already subscribed to is counted and left alone, folder
 *   included, exactly as U13's OPML import leaves it — a restore adds a past, it does not
 *   overwrite a present.
 * - It never un-does. Only entries the reader had touched are exported, so a restore can
 *   only ever turn a flag on; nothing in a file can mark a read article unread.
 * - It is idempotent. Sources dedupe on `feedUrl`, folders on name, and entry state on
 *   `(feedUrl, guid)` with a REPLACE, so running it twice equals running it once.
 *
 * The ordering problem is the interesting one. A restore lands *before* the entries it
 * describes exist, so state with nowhere to go is parked in `pending_entry_state` and
 * consumed by [EntryDao.upsertAll] when the articles arrive. Without that, the refresh a
 * reader triggers immediately after restoring would quietly undo the restore.
 */
class ProfileRepository(
    private val feedDao: FeedDao,
    private val entryDao: EntryDao,
    private val folders: FolderRepository,
    private val clock: Clock,
) {

    /** What to pre-fill the SAF create-document dialog with. */
    fun suggestedFileName(): String =
        ProfileJson.fileName(clock.instant().atZone(clock.zone).toLocalDate())

    suspend fun export(): String {
        val all = folders.folders()
        val names = all.associate { it.id to it.name }
        return ProfileJson.write(
            Profile(
                exportedAt = clock.instant(),
                folders = all
                    .filterNot { it.id == FolderEntity.UNCATEGORIZED_ID }
                    .map { ProfileFolder(name = it.name, sortIndex = it.sortIndex) },
                sources = feedDao.getAll().map { feed ->
                    ProfileSource(
                        feedUrl = feed.feedUrl,
                        title = feed.title,
                        siteUrl = feed.siteUrl,
                        customTitle = feed.customTitle,
                        // Uncategorized is written as no folder at all — it is this app's
                        // word for "unfiled", and a restore that created a folder called
                        // Uncategorized beside the built-in one would be absurd.
                        folder = names[feed.folderId]
                            ?.takeUnless { feed.folderId == FolderEntity.UNCATEGORIZED_ID },
                    )
                },
                entryState = entryDao.statesToExport().map {
                    ProfileEntryState(
                        feedUrl = it.feedUrl,
                        guid = it.guid,
                        isRead = it.isRead,
                        readAt = it.readAt,
                        isSaved = it.isSaved,
                        savedAt = it.savedAt,
                        isLiked = it.isStarred,
                        likedAt = it.starredAt,
                    )
                },
            ),
        )
    }

    suspend fun import(text: String): ProfileImportResult {
        val profile = when (val parsed = ProfileJson.read(text)) {
            is ProfileParse.Malformed -> return ProfileImportResult.Malformed(parsed.message)
            is ProfileParse.Unsupported -> return ProfileImportResult.UnsupportedVersion(
                found = parsed.schemaVersion,
                supported = ProfileJson.SCHEMA_VERSION,
            )

            is ProfileParse.Success -> parsed.profile
        }

        val resolver = FolderResolver()
        // Folders first and in full, so a restored library comes back with the empty
        // folders too — an organiser's structure is part of what was backed up, even where
        // the sources that filled it have since gone.
        profile.folders.forEach { resolver.idOf(it.name) }

        var added = 0
        var existing = 0
        val addedAt = clock.millis()
        for (source in profile.sources) {
            if (feedDao.findByUrl(source.feedUrl) != null) {
                existing++
                continue
            }
            feedDao.insert(
                FeedEntity(
                    feedUrl = source.feedUrl,
                    siteUrl = source.siteUrl,
                    title = source.title,
                    customTitle = source.customTitle,
                    faviconUrl = null,
                    // No validators and no fetch history: that is exactly the state
                    // `FeedRepository.refreshAll` reads as "never polled", so the refresh
                    // after a restore fetches everything rather than trusting a 304 from
                    // another install's ETag.
                    etag = null,
                    lastModified = null,
                    lastFetchedAt = null,
                    lastSuccessAt = null,
                    lastError = null,
                    addedAt = addedAt,
                    folderId = resolver.idOf(source.folder) ?: FolderEntity.UNCATEGORIZED_ID,
                ),
            )
            added++
        }

        // Everything is parked first and drained second, rather than each row being tested
        // for an entry as it goes. One path, so a row that finds its article during the
        // import and a row that finds it two minutes later are handled by the same code.
        entryDao.upsertPendingState(
            profile.entryState.map {
                PendingEntryStateEntity(
                    feedUrl = it.feedUrl,
                    guid = it.guid,
                    isRead = it.isRead,
                    readAt = it.readAt,
                    isSaved = it.isSaved,
                    savedAt = it.savedAt,
                    isStarred = it.isLiked,
                    starredAt = it.likedAt,
                )
            },
        )
        val applied = entryDao.applyPendingState()

        return ProfileImportResult.Restored(
            sourcesAdded = added,
            sourcesExisting = existing,
            foldersCreated = resolver.created,
            stateApplied = applied,
            statePending = entryDao.countPendingState(),
        )
    }

    /**
     * Folder name → id, created on first mention and remembered afterwards.
     *
     * Matching is [FolderRepository.createFolder]'s — case-insensitive, space-trimmed — so a
     * profile whose "Graphics" meets an existing "graphics" files sources into the folder
     * the reader can already see instead of a near-duplicate beside it. [created] counts
     * only folders that genuinely did not exist, which is what makes a second restore able
     * to report zero.
     */
    private inner class FolderResolver {
        private val byKey = mutableMapOf<String, Long>()
        var created = 0
            private set

        suspend fun idOf(name: String?): Long? {
            val clean = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val key = clean.lowercase()
            byKey[key]?.let { return it }
            val id = folders.findFolderNamed(clean)?.id
                ?: folders.createFolder(clean).also { created++ }
            byKey[key] = id
            return id
        }
    }
}
