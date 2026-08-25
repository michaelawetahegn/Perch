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
 *
 * [observeAll], [getAll], [getByFolder], [countAll] and [observeCount] all say `isSynthetic
 * = 0` — "every source" has meant "every subscribed, fetchable source" everywhere in the
 * app since v1, and PLAN-6 §0.3's saved-links row is neither. Filtering here once is what
 * makes a refresh pass, an OPML export, a profile export and a source count all skip it for
 * free, rather than four call sites each remembering to check [FeedEntity.isSynthetic]. A
 * caller that genuinely wants it back — Y04's drawer — looks it up by
 * [FeedEntity.SAVED_LINKS_FEED_URL] through [findByUrl], which is deliberately unfiltered.
 */
@Dao
interface FeedDao {

    /** Drawer order: explicit `sortIndex`, then display name, case-insensitively. */
    @Query(
        """
        SELECT * FROM feeds WHERE isSynthetic = 0
        ORDER BY sortIndex ASC, COALESCE(customTitle, title) COLLATE NOCASE ASC
        """,
    )
    fun observeAll(): Flow<List<FeedEntity>>

    @Query("SELECT * FROM feeds WHERE isSynthetic = 0 ORDER BY sortIndex ASC")
    suspend fun getAll(): List<FeedEntity>

    /** The sources filed under one folder, for a refresh scoped to it (U06). */
    @Query(
        "SELECT * FROM feeds WHERE folderId = :folderId AND isSynthetic = 0 ORDER BY sortIndex ASC",
    )
    suspend fun getByFolder(folderId: Long): List<FeedEntity>

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

    /** U09a's batch unsubscribe, in one statement so the drawer redraws once, not N times. */
    @Query("DELETE FROM feeds WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM feeds WHERE isSynthetic = 0")
    suspend fun countAll(): Int

    /**
     * How many sources exist, reactively — what tells an empty reading list whether to
     * say "add your first source" or "you're all caught up" (DESIGN.md §7).
     */
    @Query("SELECT COUNT(*) FROM feeds WHERE isSynthetic = 0")
    fun observeCount(): Flow<Int>
}
