package dev.mkiros.perch.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import dev.mkiros.perch.data.db.entity.FeedEntity
import kotlinx.coroutines.flow.Flow

/**
 * Sources. Inserting a `feedUrl` that already exists throws — duplicate handling is a
 * product decision (T16 turns it into a typed error), not something the DAO should
 * paper over by silently replacing the row and losing its fetch bookkeeping.
 */
@Dao
interface FeedDao {

    /** Drawer order: explicit `sortIndex`, then display name, case-insensitively. */
    @Query(
        """
        SELECT * FROM feeds
        ORDER BY sortIndex ASC, COALESCE(customTitle, title) COLLATE NOCASE ASC
        """,
    )
    fun observeAll(): Flow<List<FeedEntity>>

    @Query("SELECT * FROM feeds ORDER BY sortIndex ASC")
    suspend fun getAll(): List<FeedEntity>

    @Query("SELECT * FROM feeds WHERE id = :id")
    suspend fun findById(id: Long): FeedEntity?

    @Query("SELECT * FROM feeds WHERE feedUrl = :feedUrl")
    suspend fun findByUrl(feedUrl: String): FeedEntity?

    @Insert
    suspend fun insert(feed: FeedEntity): Long

    @Update
    suspend fun update(feed: FeedEntity)

    /** The rename in SPEC.md §4 — pass null to fall back to the parsed [FeedEntity.title]. */
    @Query("UPDATE feeds SET customTitle = :customTitle WHERE id = :id")
    suspend fun setCustomTitle(id: Long, customTitle: String?)

    /** Entries go with it, via `ON DELETE CASCADE`. */
    @Query("DELETE FROM feeds WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM feeds")
    suspend fun countAll(): Int

    /**
     * How many sources exist, reactively — what tells an empty reading list whether to
     * say "add your first source" or "you're all caught up" (DESIGN.md §7).
     */
    @Query("SELECT COUNT(*) FROM feeds")
    fun observeCount(): Flow<Int>
}
