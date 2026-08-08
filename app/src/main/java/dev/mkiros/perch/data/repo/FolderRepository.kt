package dev.mkiros.perch.data.repo

import dev.mkiros.perch.data.db.FolderDao
import dev.mkiros.perch.data.db.entity.FolderEntity
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Folders — the sectioning dimension of the reading list (PLAN-2 §0).
 *
 * The invariant this type exists to hold is that a folder is a grouping and never an
 * owner. Every source belongs to exactly one, "belongs to none" is spelled
 * [FolderEntity.UNCATEGORIZED_ID], and deleting a folder moves its sources rather than
 * taking them with it. Uncategorized itself is not the user's to rename or delete, which
 * is checked here rather than only hidden in the drawer.
 */
class FolderRepository(
    private val folderDao: FolderDao,
    private val clock: Clock,
) {

    /** Every folder in drawer order, Uncategorized last. */
    fun observeFolders(): Flow<List<FolderEntity>> = folderDao.observeAll().distinctUntilChanged()

    /**
     * Unread entries per folder. A folder with nothing unread is **absent** from the map,
     * not mapped to 0 — read it as `counts[id] ?: 0`.
     */
    fun observeUnreadCountsByFolder(): Flow<Map<Long, Int>> =
        folderDao.observeUnreadCountsByFolder().distinctUntilChanged()

    suspend fun findFolder(id: Long): FolderEntity? = folderDao.findById(id)

    /**
     * Creates a folder, or returns the one that is already called [name]. Matching is
     * case-insensitive and ignores surrounding space, because a person who types
     * "graphics" when "Graphics" exists means the folder they can already see — and U13's
     * OPML import, which creates folders wholesale from another reader's file, would
     * otherwise fill the drawer with near-duplicates.
     *
     * New folders are appended after the existing ones; the user reorders from there.
     */
    suspend fun createFolder(name: String): Long {
        val clean = name.clean()
        folderDao.findByName(clean)?.let { return it.id }
        return folderDao.insert(
            FolderEntity(
                name = clean,
                sortIndex = folderDao.maxSortIndex() + 1,
                createdAt = clock.millis(),
            ),
        )
    }

    /**
     * Renames [id]. Returns false, changing nothing, for Uncategorized — it is the
     * fallback every sourceless source lands in, so its name is part of the app rather
     * than part of the user's data.
     */
    suspend fun renameFolder(id: Long, name: String): Boolean {
        if (id == FolderEntity.UNCATEGORIZED_ID) return false
        folderDao.setName(id, name.clean())
        return true
    }

    /**
     * Deletes [id] and moves its sources to Uncategorized. Returns false for
     * Uncategorized, which has nowhere to move its sources to.
     */
    suspend fun deleteFolder(id: Long): Boolean {
        if (id == FolderEntity.UNCATEGORIZED_ID) return false
        folderDao.deleteAndReassign(id)
        return true
    }

    /** Files one source under [folderId], leaving the rest of the row alone. */
    suspend fun moveSource(feedId: Long, folderId: Long) = folderDao.setFolder(feedId, folderId)

    private fun String.clean(): String =
        trim().also { require(it.isNotEmpty()) { "a folder name cannot be blank" } }
}
