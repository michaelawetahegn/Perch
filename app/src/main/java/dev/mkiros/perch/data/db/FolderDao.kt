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
     * Drawer order (PLAN-3 §0): alphabetical, case-insensitive, with Uncategorized pinned
     * last whatever it is called. This replaces PLAN-2 §0's `sortIndex` order — no reorder
     * UI was ever built, so `sortIndex` only ever meant creation order. Pinning it in SQL
     * rather than in the drawer means every caller — drawer, home sections, OPML export —
     * gets the same order for free; the same clause is stated in [EntryQueries.LIST_ITEMS],
     * which sections the list, and the two must never disagree.
     *
     * `COLLATE NOCASE` folds ASCII and nothing else, so `Émacs` sorts by its UTF-8 bytes,
     * after every plain name rather than among the E's. Accepted: the alternative is an ICU
     * collation to carry, for a case a reading list hits about never. `FolderDaoTest` pins
     * the behaviour so it is a decision and not a surprise.
     */
    @Query(
        """
        SELECT * FROM folders
        ORDER BY (id = 1) ASC, name COLLATE NOCASE ASC
        """,
    )
    abstract fun observeAll(): Flow<List<FolderEntity>>

    /** The one-shot read of [observeAll], and it must sort identically. */
    @Query("SELECT * FROM folders ORDER BY (id = 1) ASC, name COLLATE NOCASE ASC")
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

    /**
     * Which sources are filed under [folderId]. U09a's undo needs this *before* the delete
     * runs: once the reassignment has happened every one of them says Uncategorized, and
     * the memberships the reader is being offered back no longer exist anywhere.
     */
    @Query("SELECT id FROM feeds WHERE folderId = :folderId")
    abstract suspend fun feedIdsIn(folderId: Long): List<Long>

    /**
     * Puts folders back under the ids they had. `@Insert` rather than an upsert, and with
     * the id carried in the row rather than regenerated: a restored folder that came back
     * under a fresh id would look right in the drawer while every membership pointing at
     * it dangled.
     */
    @Insert
    abstract suspend fun insertAll(folders: List<FolderEntity>)

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
