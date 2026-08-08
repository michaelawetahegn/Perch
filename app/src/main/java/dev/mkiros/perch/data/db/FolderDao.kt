package dev.mkiros.perch.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.Query
import androidx.room.Transaction
import dev.mkiros.perch.data.db.entity.FolderEntity
import kotlinx.coroutines.flow.Flow

/**
 * Folders. Inserting a name that already exists throws — matching an existing folder
 * case-insensitively is a product decision (`FolderRepository.createFolder`, and U13's
 * OPML import), not something the DAO should paper over.
 */
@Dao
abstract class FolderDao {

    /**
     * Drawer order (PLAN-2 §0): the user's `sortIndex`, with Uncategorized pinned last
     * whatever its own value. Pinning it in SQL rather than in the drawer means every
     * caller — drawer, home sections, OPML export — gets the same order for free.
     */
    @Query(
        """
        SELECT * FROM folders
        ORDER BY (id = 1) ASC, sortIndex ASC, name COLLATE NOCASE ASC
        """,
    )
    abstract fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY (id = 1) ASC, sortIndex ASC")
    abstract suspend fun getAll(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    abstract suspend fun findById(id: Long): FolderEntity?

    /**
     * Case-insensitive on purpose: the unique index is SQLite's default, byte-exact
     * comparison, so "Graphics" and "graphics" are two rows as far as the constraint is
     * concerned but one folder as far as a person is concerned. This is what the
     * repository looks through before inserting.
     */
    @Query("SELECT * FROM folders WHERE name = :name COLLATE NOCASE")
    abstract suspend fun findByName(name: String): FolderEntity?

    @Query("SELECT COALESCE(MAX(sortIndex), 0) FROM folders WHERE id <> 1")
    abstract suspend fun maxSortIndex(): Int

    @Insert
    abstract suspend fun insert(folder: FolderEntity): Long

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    abstract suspend fun setName(id: Long, name: String)

    /**
     * Deletes [folderId] and moves its sources to Uncategorized, in that order and in one
     * transaction. A folder is a grouping, never an owner: deleting one must never cost
     * the user a subscription, so the reassignment happens first and the foreign key on
     * `feeds.folderId` refuses the delete if it somehow did not.
     */
    @Transaction
    open suspend fun deleteAndReassign(folderId: Long) {
        if (folderId == FolderEntity.UNCATEGORIZED_ID) return
        reassign(from = folderId, to = FolderEntity.UNCATEGORIZED_ID)
        deleteById(folderId)
    }

    @Query("UPDATE feeds SET folderId = :to WHERE folderId = :from")
    abstract suspend fun reassign(from: Long, to: Long)

    @Query("DELETE FROM folders WHERE id = :id")
    abstract suspend fun deleteById(id: Long)

    @Query("UPDATE feeds SET folderId = :folderId WHERE id = :feedId")
    abstract suspend fun setFolder(feedId: Long, folderId: Long)

    /**
     * Unread entries per folder, counted by SQLite.
     *
     * Same shape and same trap as [EntryDao.observeUnreadCountsByFeed]: this is a
     * `GROUP BY`, so a folder whose every entry is read is **absent** from the map rather
     * than mapped to 0. Read it as `counts[id] ?: 0`. Summing per-feed counts in Kotlin
     * instead would load every row to produce one integer per folder.
     */
    @Query(
        """
        SELECT feeds.folderId AS folderId, COUNT(*) AS unreadCount
        FROM entries JOIN feeds ON entries.feedId = feeds.id
        WHERE entries.isRead = 0
        GROUP BY feeds.folderId
        """,
    )
    abstract fun observeUnreadCountsByFolder():
        Flow<Map<@MapColumn("folderId") Long, @MapColumn("unreadCount") Int>>
}
